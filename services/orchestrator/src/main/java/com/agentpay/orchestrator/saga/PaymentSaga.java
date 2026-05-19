package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.agents.support.AgentVerdictRepository;
import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.observability.CaseObservation;
import com.agentpay.orchestrator.observability.SagaMetrics;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.agentpay.orchestrator.psp.MockPspClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single explicit Saga coordinator (CLAUDE.md §9: no Spring State Machine). Drives forward steps,
 * persists every transition transactionally with state and outbox writes (NFR-R-003 via outbox
 * pattern), and is idempotent on case_id (FR-O-007).
 *
 * <p>The PSP call (HTTP) deliberately happens OUTSIDE any DB transaction; we don't want a JPA
 * connection held open across network I/O. The terminal state write that follows is its own short
 * transaction.
 *
 * <p>Persistence model (Iter 4b.3, FR-O-005): agentMetadata is written to {@code
 * cases.agent_metadata_jsonb} in the same transaction as the initial INITIATED insert; the
 * supervisor's Decision is written to {@code cases.decision_jsonb} in the same transaction as the
 * REVIEWING→{APPROVED,DECLINED,SUSPENDED_FOR_REVIEW} transition. Both replace the in-memory
 * ConcurrentHashMap caches deferred from Iter 4b.2.
 */
@Service
public class PaymentSaga {

  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

  private final CaseRepository cases;
  private final SagaTransitionRepository transitions;
  private final EventOutboxRepository outbox;
  private final AgentVerdictRepository verdicts;
  private final Supervisor supervisor;
  private final MockPspClient psp;
  private final PaymentEventSerializer serializer;
  private final HumanApprovalRequestSerializer approvalRequestSerializer;
  private final CaseObservation caseObservation;
  private final SagaMetrics sagaMetrics;
  private final BudgetExceededSerializer budgetExceededSerializer;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final java.math.BigDecimal perCaseBudgetUsd;
  private final String paymentEventsTopic;
  private final String humanApprovalRequestsTopic;
  private final String caseBudgetExceededTopic;

  // Proxy reference to self. Required so the @Transactional methods below run inside an actual
  // JPA transaction even when invoked from another method on this class — direct `this.xxx()`
  // calls bypass the AOP proxy and the annotation has no effect, which leaves entity changes
  // unflushed and spins driveForward()'s while-loop forever.
  private PaymentSaga self;

  public PaymentSaga(
      CaseRepository cases,
      SagaTransitionRepository transitions,
      EventOutboxRepository outbox,
      AgentVerdictRepository verdicts,
      Supervisor supervisor,
      MockPspClient psp,
      PaymentEventSerializer serializer,
      HumanApprovalRequestSerializer approvalRequestSerializer,
      BudgetExceededSerializer budgetExceededSerializer,
      CaseObservation caseObservation,
      SagaMetrics sagaMetrics,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${agentpay.budget.per_case_usd:0.10}") java.math.BigDecimal perCaseBudgetUsd,
      @Value("${agentpay.events.topic}") String paymentEventsTopic,
      @Value("${agentpay.events.human-approval-requests-topic}") String humanApprovalRequestsTopic,
      @Value("${agentpay.events.case-budget-exceeded-topic}") String caseBudgetExceededTopic) {
    this.cases = cases;
    this.transitions = transitions;
    this.outbox = outbox;
    this.verdicts = verdicts;
    this.supervisor = supervisor;
    this.psp = psp;
    this.serializer = serializer;
    this.approvalRequestSerializer = approvalRequestSerializer;
    this.budgetExceededSerializer = budgetExceededSerializer;
    this.caseObservation = caseObservation;
    this.sagaMetrics = sagaMetrics;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.perCaseBudgetUsd = perCaseBudgetUsd;
    this.paymentEventsTopic = paymentEventsTopic;
    this.humanApprovalRequestsTopic = humanApprovalRequestsTopic;
    this.caseBudgetExceededTopic = caseBudgetExceededTopic;
  }

  @Autowired
  void setSelf(@Lazy PaymentSaga self) {
    this.self = self;
  }

  /**
   * Idempotent start (FR-O-007): duplicate case_id returns the current state with duplicate=true.
   *
   * <p>The intent-token jti is captured here (separately from PaymentContext) so the §7.2.2
   * PaymentContext stays in line with REQUIREMENTS.md — the jti is a cases-table column, not part
   * of what the decision plane sees.
   */
  public StartResult start(PaymentContext ctx, UUID intentTokenJti) {
    return caseObservation.observe(
        ctx.caseId(),
        ctx.agentId(),
        ctx.merchantId(),
        () -> {
          var existing = cases.findById(ctx.caseId());
          if (existing.isPresent()) {
            log.info(
                "idempotent duplicate POST case_id={} current_state={}",
                ctx.caseId(),
                existing.get().getState());
            return new CaseObservation.Outcome<>(
                new StartResult(ctx.caseId(), existing.get().getState(), true),
                existing.get().getState().name(),
                existing.get().getCostUsd());
          }
          self.createInitiated(ctx, intentTokenJti);
          driveForward(ctx.caseId());
          self.finalizeCost(ctx.caseId());
          TerminalView view = self.readTerminalView(ctx.caseId());
          return new CaseObservation.Outcome<>(
              new StartResult(ctx.caseId(), view.state(), false),
              view.state().name(),
              view.costUsd());
        });
  }

  /** Resume entry point for SagaRecoveryRunner (NFR-R-002) and human-approval handlers. */
  public void driveForward(String caseId) {
    while (true) {
      CaseSnapshot snap = self.loadSnapshot(caseId);
      SagaState state = snap.state();
      if (state.isTerminal()) {
        return;
      }
      if (state == SagaState.SUSPENDED_FOR_REVIEW) {
        // Wait for human.approval.granted / .denied event consumed by
        // HumanApprovalResponseListener.
        return;
      }
      stepOnce(snap);
    }
  }

  /**
   * Entry point for HumanApprovalResponseListener on GRANTED. Idempotent: a redelivered response
   * for a case that has already left SUSPENDED_FOR_REVIEW is a silent no-op.
   *
   * <p>Opens a fresh {@code agentpay.case} observation — this is a <em>separate trace</em> from the
   * one emitted by the original {@link #start} call (see {@link CaseObservation}); correlation via
   * {@code case_id} is left to a future iteration.
   */
  public void onApprovalGranted(String caseId) {
    var snap = self.loadSnapshot(caseId);
    caseObservation.observe(
        caseId,
        snap.agentId(),
        snap.merchantId(),
        () -> {
          self.markApprovalGranted(caseId);
          driveForward(caseId);
          self.finalizeCost(caseId);
          TerminalView view = self.readTerminalView(caseId);
          return new CaseObservation.Outcome<Void>(null, view.state().name(), view.costUsd());
        });
  }

  /**
   * Entry point for HumanApprovalResponseListener on DENIED. Transitions SUSPENDED_FOR_REVIEW →
   * DECLINED with reason_class HUMAN_REVIEW_DENIED. Idempotent on already-terminal cases.
   *
   * <p>Opens a fresh {@code agentpay.case} observation — separate trace from the original; see
   * {@link CaseObservation} for the deferred correlation note.
   */
  public void onApprovalDenied(String caseId, String reason) {
    var snap = self.loadSnapshot(caseId);
    caseObservation.observe(
        caseId,
        snap.agentId(),
        snap.merchantId(),
        () -> {
          self.markApprovalDenied(caseId, reason);
          self.finalizeCost(caseId);
          TerminalView view = self.readTerminalView(caseId);
          return new CaseObservation.Outcome<Void>(null, view.state().name(), view.costUsd());
        });
  }

  private void stepOnce(CaseSnapshot snap) {
    switch (snap.state()) {
      case INITIATED ->
          self.recordTransition(snap.caseId(), SagaState.INITIATED, SagaState.HELD, "funds held");
      case HELD ->
          self.recordTransition(
              snap.caseId(), SagaState.HELD, SagaState.REVIEWING, "decision plane invoked");
      case REVIEWING -> applyDecision(snap);
      case APPROVED ->
          self.recordTransition(
              snap.caseId(), SagaState.APPROVED, SagaState.ROUTED, "route selected");
      case ROUTED -> commitOrCompensate(snap);
      default -> throw new IllegalStateException("non-driveable state: " + snap.state());
    }
  }

  private void applyDecision(CaseSnapshot snap) {
    Decision d = supervisor.decide(toContext(snap));
    self.applyDecisionAtomic(snap.caseId(), d);
  }

  private void commitOrCompensate(CaseSnapshot snap) {
    Decision d = hydrateDecision(snap);
    // route is present iff outcome == APPROVED; commitOrCompensate is only reached from ROUTED
    // which is only reached from APPROVED, so unwrap is safe.
    RouteRecommendation route =
        d.route().orElseThrow(() -> new IllegalStateException("ROUTED case missing route"));
    var response =
        psp.charge(
            new MockPspClient.ChargeRequest(
                snap.caseId(), snap.amount(), snap.currency(), route.pspId(), route.routeId()));
    if (response.success()) {
      self.recordTerminal(snap.caseId(), SagaState.ROUTED, SagaState.COMMITTED, null);
    } else {
      // FR-O-006: PSP failure → COMPENSATED. Reason class carries the ISO 20022 code.
      self.recordTerminal(
          snap.caseId(), SagaState.ROUTED, SagaState.COMPENSATED, response.reasonCode());
    }
  }

  private Decision hydrateDecision(CaseSnapshot snap) {
    JsonNode tree = snap.decisionJsonb();
    if (tree == null || tree.isNull()) {
      throw new IllegalStateException(
          "case " + snap.caseId() + " in state " + snap.state() + " missing decision_jsonb");
    }
    try {
      return objectMapper.treeToValue(tree, Decision.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(
          "failed to hydrate Decision for case " + snap.caseId() + " from JSONB", e);
    }
  }

  /**
   * Maps a DECLINED Decision to the reason_class string surfaced on the Kafka payment.declined
   * event (REQUIREMENTS §10.2). Compliance-driven declines must carry COMPLIANCE_SANCTIONS_MATCH so
   * the on-call eval pipeline can branch on it; risk-driven declines carry RISK_HIGH.
   */
  private static String reasonClassFor(Decision d) {
    if (d.compliance() != null && d.compliance().outcome() == Outcome.FAIL) {
      return "COMPLIANCE_SANCTIONS_MATCH";
    }
    if (d.riskScore() >= 80) {
      return "RISK_HIGH";
    }
    return "DECISION_DECLINED";
  }

  private static String joinRationale(java.util.List<String> rationale) {
    return rationale.isEmpty() ? "" : String.join(" | ", rationale);
  }

  // --- @Transactional persistence methods. All entity reads happen inside these boundaries so
  // that JSONB columns are safely materialised against an open Hibernate session. ---

  /**
   * Read-only snapshot used by {@link #driveForward}. JSONB fields are EAGER on {@link CaseEntity},
   * but loading inside an explicit transaction (open-in-view is false) guarantees the conversion
   * runs while the session is open.
   */
  @Transactional(value = "transactionManager", readOnly = true)
  CaseSnapshot loadSnapshot(String caseId) {
    return CaseSnapshot.from(cases.findById(caseId).orElseThrow());
  }

  @Transactional("transactionManager")
  void createInitiated(PaymentContext ctx, UUID intentTokenJti) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    CaseEntity entity =
        new CaseEntity(
            ctx.caseId(),
            ctx.agentId(),
            ctx.merchantId(),
            ctx.amount(),
            ctx.currency(),
            SagaState.INITIATED,
            intentTokenJti,
            now);
    Map<String, String> meta = ctx.agentMetadata();
    if (meta != null && !meta.isEmpty()) {
      entity.setAgentMetadataJsonb(Map.copyOf(meta));
    }
    cases.save(entity);
    transitions.save(
        new SagaTransitionEntity(
            ctx.caseId(), null, SagaState.INITIATED, "intent token accepted", now));
  }

  @Transactional("transactionManager")
  void recordTransition(String caseId, SagaState from, SagaState to, String reason) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    CaseEntity c = cases.findById(caseId).orElseThrow();
    if (c.getState() != from) {
      // Concurrent recovery + request: another driver already advanced this case. Bail out;
      // driveForward's outer loop will re-read the new state and continue.
      log.info(
          "transition skipped: case_id={} expected_from={} actual={}", caseId, from, c.getState());
      return;
    }
    c.setState(to, now);
    transitions.save(new SagaTransitionEntity(caseId, from, to, reason, now));
  }

  /**
   * Persists the Decision and applies the REVIEWING→{APPROVED,DECLINED,SUSPENDED_FOR_REVIEW}
   * transition atomically with the matching outbox row (FR-O-004/005/006, NFR-R-003).
   */
  @Transactional("transactionManager")
  void applyDecisionAtomic(String caseId, Decision decision) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    CaseEntity c = cases.findById(caseId).orElseThrow();
    if (c.getState() != SagaState.REVIEWING) {
      log.info(
          "applyDecision skipped: case_id={} expected_from=REVIEWING actual={}",
          caseId,
          c.getState());
      return;
    }
    // Decision is needed by commitOrCompensate (APPROVED → ROUTED → commit reads route) and by
    // onApprovalGranted (SUSPENDED → APPROVED → ROUTED → commit), so persist on every branch.
    // valueToTree preserves JSON shape exactly (List<String> → ArrayNode), which convertValue via
    // Map.class did not when round-tripped through Hibernate's JsonFormatMapper.
    c.setDecisionJsonb(objectMapper.valueToTree(decision));

    switch (decision.outcome()) {
      case APPROVED -> {
        c.setState(SagaState.APPROVED, now);
        transitions.save(
            new SagaTransitionEntity(
                caseId,
                SagaState.REVIEWING,
                SagaState.APPROVED,
                joinRationale(decision.rationale()),
                now));
      }
      case DECLINED -> {
        String reasonClass = reasonClassFor(decision);
        c.setState(SagaState.DECLINED, now);
        transitions.save(
            new SagaTransitionEntity(
                caseId, SagaState.REVIEWING, SagaState.DECLINED, reasonClass, now));
        byte[] payload = serializer.toBytes(c, SagaState.DECLINED, reasonClass);
        outbox.save(new EventOutboxEntity(caseId, paymentEventsTopic, caseId, payload, now));
        sagaMetrics.countTerminal(SagaState.DECLINED);
      }
      case REVIEW -> {
        c.setState(SagaState.SUSPENDED_FOR_REVIEW, now);
        if (decision.budgetExceeded()) {
          // Iter 6 (NFR-COST-001, Scenario F): supervisor short-circuited because running cost
          // breached per_case_usd. Publish to case.budget_exceeded INSTEAD of
          // human.approval.requests — observability owns this branch, not on-call. The audit
          // reason on the transition row records the breach so the saga history is self-describing.
          java.math.BigDecimal running = verdicts.sumCostByCaseId(caseId);
          String reason =
              "budget exceeded: running=$"
                  + running.toPlainString()
                  + " budget=$"
                  + perCaseBudgetUsd.toPlainString();
          transitions.save(
              new SagaTransitionEntity(
                  caseId, SagaState.REVIEWING, SagaState.SUSPENDED_FOR_REVIEW, reason, now));
          byte[] payload = budgetExceededSerializer.toBytes(c, running, perCaseBudgetUsd);
          outbox.save(new EventOutboxEntity(caseId, caseBudgetExceededTopic, caseId, payload, now));
        } else {
          // D3 (Iter 4b.3): no reason text on the REVIEW transition — null keeps the audit row
          // consistent with how applyDecision's other branches encode their rationale.
          transitions.save(
              new SagaTransitionEntity(
                  caseId, SagaState.REVIEWING, SagaState.SUSPENDED_FOR_REVIEW, null, now));
          byte[] payload = approvalRequestSerializer.toBytes(c, decision);
          outbox.save(
              new EventOutboxEntity(caseId, humanApprovalRequestsTopic, caseId, payload, now));
        }
      }
    }
  }

  /**
   * Terminal transition + outbox row in the SAME JPA transaction (NFR-R-003 via outbox pattern).
   * Used for the PSP commit/compensate branch; the supervisor-driven DECLINED branch is handled by
   * {@link #applyDecisionAtomic}.
   */
  @Transactional("transactionManager")
  void recordTerminal(String caseId, SagaState from, SagaState terminal, String reasonClass) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    CaseEntity c = cases.findById(caseId).orElseThrow();
    if (c.getState() != from) {
      log.info(
          "terminal skipped: case_id={} expected_from={} actual={}", caseId, from, c.getState());
      return;
    }
    c.setState(terminal, now);
    transitions.save(new SagaTransitionEntity(caseId, from, terminal, reasonClass, now));
    byte[] payload = serializer.toBytes(c, terminal, reasonClass);
    outbox.save(new EventOutboxEntity(caseId, paymentEventsTopic, caseId, payload, now));
    sagaMetrics.countTerminal(terminal);
  }

  /**
   * Idempotent handler for human.approval GRANTED. State must be SUSPENDED_FOR_REVIEW; any other
   * state (including the terminal ones) means a redelivered or out-of-order response → no-op.
   */
  @Transactional("transactionManager")
  void markApprovalGranted(String caseId) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var maybe = cases.findById(caseId);
    if (maybe.isEmpty()) {
      log.warn("approval granted for unknown case_id={} — ignored", caseId);
      return;
    }
    CaseEntity c = maybe.get();
    if (c.getState() != SagaState.SUSPENDED_FOR_REVIEW) {
      log.info(
          "approval granted ignored: case_id={} not in SUSPENDED_FOR_REVIEW (actual={})",
          caseId,
          c.getState());
      return;
    }
    c.setState(SagaState.APPROVED, now);
    transitions.save(
        new SagaTransitionEntity(
            caseId, SagaState.SUSPENDED_FOR_REVIEW, SagaState.APPROVED, "human granted", now));
  }

  /**
   * Idempotent handler for human.approval DENIED. SUSPENDED_FOR_REVIEW → DECLINED with reason_class
   * HUMAN_REVIEW_DENIED on the outbox row. The decision aggregation that put the case in REVIEW is
   * preserved in {@code decision_jsonb}; the {@code reason} arg is only used as the audit text.
   */
  @Transactional("transactionManager")
  void markApprovalDenied(String caseId, String reason) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var maybe = cases.findById(caseId);
    if (maybe.isEmpty()) {
      log.warn("approval denied for unknown case_id={} — ignored", caseId);
      return;
    }
    CaseEntity c = maybe.get();
    if (c.getState() != SagaState.SUSPENDED_FOR_REVIEW) {
      log.info(
          "approval denied ignored: case_id={} not in SUSPENDED_FOR_REVIEW (actual={})",
          caseId,
          c.getState());
      return;
    }
    String reasonClass = "HUMAN_REVIEW_DENIED";
    c.setState(SagaState.DECLINED, now);
    transitions.save(
        new SagaTransitionEntity(
            caseId, SagaState.SUSPENDED_FOR_REVIEW, SagaState.DECLINED, reason, now));
    byte[] payload = serializer.toBytes(c, SagaState.DECLINED, reasonClass);
    outbox.save(new EventOutboxEntity(caseId, paymentEventsTopic, caseId, payload, now));
    sagaMetrics.countTerminal(SagaState.DECLINED);
  }

  /**
   * Sums {@code agent_verdicts.cost_usd} for this case and persists the total on {@code
   * cases.cost_usd} (NFR-O-003). Runs in its own transaction so a recompute is safe to call after
   * any number of intermediate transitions; {@link AgentVerdictRepository#sumCostByCaseId} returns
   * 0 when no verdicts exist (idempotent no-op for INITIATED→HELD-only flows).
   */
  @Transactional("transactionManager")
  void finalizeCost(String caseId) {
    BigDecimal total = verdicts.sumCostByCaseId(caseId);
    cases.findById(caseId).ifPresent(c -> c.setCostUsd(total));
    // NFR-O-004 (cost-per-case panel): emit the per-case cost as a DistributionSummary sample.
    // Counter increment is post-write so a rollback would leave the counter slightly over-counted;
    // acceptable trade-off — metrics are diagnostic, not authoritative.
    sagaMetrics.recordCaseCost(total);
  }

  /**
   * Read-only view used by {@link #start} and the approval entry points to populate span attributes
   * after the saga has settled. Separate from {@link #loadSnapshot} because callers here only need
   * the post-cost-finalize state and the persisted total.
   */
  @Transactional(value = "transactionManager", readOnly = true)
  TerminalView readTerminalView(String caseId) {
    CaseEntity c = cases.findById(caseId).orElseThrow();
    return new TerminalView(c.getState(), c.getCostUsd());
  }

  record TerminalView(SagaState state, BigDecimal costUsd) {}

  private static PaymentContext toContext(CaseSnapshot snap) {
    return new PaymentContext(
        snap.caseId(),
        snap.agentId(),
        snap.merchantId(),
        snap.amount(),
        snap.currency(),
        null,
        snap.agentMetadata());
  }

  public record StartResult(String caseId, SagaState state, boolean duplicate) {}
}
