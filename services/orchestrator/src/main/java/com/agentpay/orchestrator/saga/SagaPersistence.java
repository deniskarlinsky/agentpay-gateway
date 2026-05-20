package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.agents.support.AgentVerdictRepository;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.observability.SagaMetrics;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every transactional persistence step for the saga. Extracted from {@link PaymentSaga} so
 * the @Transactional methods are reached through Spring's AOP proxy boundary instead of a
 * {@code @Lazy} self-injection: {@code PaymentSaga} now calls {@code persistence.xxx()} which
 * traverses a real proxy, letting the {@code @Transactional} annotation take effect.
 *
 * <p>The class is otherwise behaviour-preserving — every method body, exception path, and metric
 * call is identical to the pre-extraction code; only the owner changed.
 */
@Service
public class SagaPersistence {

  private static final Logger log = LoggerFactory.getLogger(SagaPersistence.class);

  private final CaseRepository cases;
  private final SagaTransitionRepository transitions;
  private final EventOutboxRepository outbox;
  private final AgentVerdictRepository verdicts;
  private final PaymentEventSerializer serializer;
  private final HumanApprovalRequestSerializer approvalRequestSerializer;
  private final BudgetExceededSerializer budgetExceededSerializer;
  private final SagaMetrics sagaMetrics;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final BigDecimal perCaseBudgetUsd;
  private final String paymentEventsTopic;
  private final String humanApprovalRequestsTopic;
  private final String caseBudgetExceededTopic;

  public SagaPersistence(
      CaseRepository cases,
      SagaTransitionRepository transitions,
      EventOutboxRepository outbox,
      AgentVerdictRepository verdicts,
      PaymentEventSerializer serializer,
      HumanApprovalRequestSerializer approvalRequestSerializer,
      BudgetExceededSerializer budgetExceededSerializer,
      SagaMetrics sagaMetrics,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${agentpay.budget.per_case_usd:0.10}") BigDecimal perCaseBudgetUsd,
      @Value("${agentpay.events.topic}") String paymentEventsTopic,
      @Value("${agentpay.events.human-approval-requests-topic}") String humanApprovalRequestsTopic,
      @Value("${agentpay.events.case-budget-exceeded-topic}") String caseBudgetExceededTopic) {
    this.cases = cases;
    this.transitions = transitions;
    this.outbox = outbox;
    this.verdicts = verdicts;
    this.serializer = serializer;
    this.approvalRequestSerializer = approvalRequestSerializer;
    this.budgetExceededSerializer = budgetExceededSerializer;
    this.sagaMetrics = sagaMetrics;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.perCaseBudgetUsd = perCaseBudgetUsd;
    this.paymentEventsTopic = paymentEventsTopic;
    this.humanApprovalRequestsTopic = humanApprovalRequestsTopic;
    this.caseBudgetExceededTopic = caseBudgetExceededTopic;
  }

  /**
   * Read-only snapshot used by {@link PaymentSaga#driveForward}. JSONB fields are EAGER on {@link
   * CaseEntity}, but loading inside an explicit transaction (open-in-view is false) guarantees the
   * conversion runs while the session is open.
   */
  @Transactional(readOnly = true)
  CaseSnapshot loadSnapshot(String caseId) {
    return CaseSnapshot.from(cases.findById(caseId).orElseThrow());
  }

  @Transactional
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

  @Transactional
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
  @Transactional
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
          BigDecimal running = verdicts.sumCostByCaseId(caseId);
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
  @Transactional
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
  @Transactional
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
  @Transactional
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
  @Transactional
  void finalizeCost(String caseId) {
    BigDecimal total = verdicts.sumCostByCaseId(caseId);
    cases.findById(caseId).ifPresent(c -> c.setCostUsd(total));
    // NFR-O-004 (cost-per-case panel): emit the per-case cost as a DistributionSummary sample.
    // Counter increment is post-write so a rollback would leave the counter slightly over-counted;
    // acceptable trade-off — metrics are diagnostic, not authoritative.
    sagaMetrics.recordCaseCost(total);
  }

  /**
   * Read-only view used by {@link PaymentSaga#start} and the approval entry points to populate span
   * attributes after the saga has settled. Separate from {@link #loadSnapshot} because callers here
   * only need the post-cost-finalize state and the persisted total.
   */
  @Transactional(readOnly = true)
  TerminalView readTerminalView(String caseId) {
    CaseEntity c = cases.findById(caseId).orElseThrow();
    return new TerminalView(c.getState(), c.getCostUsd());
  }

  record TerminalView(SagaState state, BigDecimal costUsd) {}

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
}
