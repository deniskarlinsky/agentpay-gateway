package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.agentpay.orchestrator.psp.MockPspClient;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 */
@Service
public class PaymentSaga {

  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

  private final CaseRepository cases;
  private final SagaTransitionRepository transitions;
  private final EventOutboxRepository outbox;
  private final Supervisor supervisor;
  private final MockPspClient psp;
  private final PaymentEventSerializer serializer;
  private final Clock clock;
  private final String topic;

  // Proxy reference to self. Required so the @Transactional methods below run inside an actual
  // JPA transaction even when invoked from another method on this class — direct `this.xxx()`
  // calls bypass the AOP proxy and the annotation has no effect, which leaves entity changes
  // unflushed and spins driveForward()'s while-loop forever.
  private PaymentSaga self;

  public PaymentSaga(
      CaseRepository cases,
      SagaTransitionRepository transitions,
      EventOutboxRepository outbox,
      Supervisor supervisor,
      MockPspClient psp,
      PaymentEventSerializer serializer,
      Clock clock,
      @Value("${agentpay.events.topic}") String topic) {
    this.cases = cases;
    this.transitions = transitions;
    this.outbox = outbox;
    this.supervisor = supervisor;
    this.psp = psp;
    this.serializer = serializer;
    this.clock = clock;
    this.topic = topic;
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
    var existing = cases.findById(ctx.caseId());
    if (existing.isPresent()) {
      log.info(
          "idempotent duplicate POST case_id={} current_state={}",
          ctx.caseId(),
          existing.get().getState());
      return new StartResult(ctx.caseId(), existing.get().getState(), true);
    }
    self.createInitiated(ctx, intentTokenJti);
    driveForward(ctx.caseId());
    SagaState terminal = cases.findById(ctx.caseId()).orElseThrow().getState();
    return new StartResult(ctx.caseId(), terminal, false);
  }

  /** Resume entry point for SagaRecoveryRunner (NFR-R-002). */
  public void driveForward(String caseId) {
    while (true) {
      CaseEntity c = cases.findById(caseId).orElseThrow();
      SagaState state = c.getState();
      if (state.isTerminal()) {
        return;
      }
      if (state == SagaState.SUSPENDED_FOR_REVIEW) {
        // Iter 4: wait for human.approval.granted / .denied event.
        return;
      }
      stepOnce(c);
    }
  }

  private void stepOnce(CaseEntity c) {
    switch (c.getState()) {
      case INITIATED ->
          self.recordTransition(c.getCaseId(), SagaState.INITIATED, SagaState.HELD, "funds held");
      case HELD ->
          self.recordTransition(
              c.getCaseId(), SagaState.HELD, SagaState.REVIEWING, "decision plane invoked");
      case REVIEWING -> applyDecision(c);
      case APPROVED ->
          self.recordTransition(
              c.getCaseId(), SagaState.APPROVED, SagaState.ROUTED, "route selected");
      case ROUTED -> commitOrCompensate(c);
      default -> throw new IllegalStateException("non-driveable state: " + c.getState());
    }
  }

  private void applyDecision(CaseEntity c) {
    // TODO (Iter 4b.2): persist the Decision (route + scores) so resume after crash uses the same
    // route. Stub supervisor is deterministic so re-derivation is currently safe.
    Decision d = supervisor.decide(toContext(c));
    switch (d.outcome()) {
      case APPROVED ->
          self.recordTransition(
              c.getCaseId(), SagaState.REVIEWING, SagaState.APPROVED, joinRationale(d.rationale()));
      case DECLINED ->
          self.recordTerminal(c, SagaState.REVIEWING, SagaState.DECLINED, "DECISION_DECLINED");
      case REVIEW ->
          self.recordTransition(
              c.getCaseId(),
              SagaState.REVIEWING,
              SagaState.SUSPENDED_FOR_REVIEW,
              "human review required");
    }
  }

  private void commitOrCompensate(CaseEntity c) {
    Decision d = supervisor.decide(toContext(c));
    // route is present iff outcome == APPROVED; commitOrCompensate is only reached from ROUTED
    // which is only reached from APPROVED, so unwrap is safe.
    var route = d.route().orElseThrow(() -> new IllegalStateException("ROUTED case missing route"));
    var response =
        psp.charge(
            new MockPspClient.ChargeRequest(
                c.getCaseId(), c.getAmount(), c.getCurrency(), route.pspId(), route.routeId()));
    if (response.success()) {
      self.recordTerminal(c, SagaState.ROUTED, SagaState.COMMITTED, null);
    } else {
      // FR-O-006: PSP failure → COMPENSATED. Reason class carries the ISO 20022 code.
      self.recordTerminal(c, SagaState.ROUTED, SagaState.COMPENSATED, response.reasonCode());
    }
  }

  private static String joinRationale(java.util.List<String> rationale) {
    return rationale.isEmpty() ? "" : String.join(" | ", rationale);
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
   * Terminal transition + outbox row in the SAME JPA transaction (NFR-R-003 via outbox pattern).
   * OutboxPublisher drains to Kafka.
   */
  @Transactional("transactionManager")
  void recordTerminal(
      CaseEntity loadedCase, SagaState from, SagaState terminal, String reasonClass) {
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    CaseEntity c = cases.findById(loadedCase.getCaseId()).orElseThrow();
    if (c.getState() != from) {
      log.info(
          "terminal skipped: case_id={} expected_from={} actual={}",
          c.getCaseId(),
          from,
          c.getState());
      return;
    }
    c.setState(terminal, now);
    transitions.save(new SagaTransitionEntity(c.getCaseId(), from, terminal, reasonClass, now));
    byte[] payload = serializer.toBytes(c, terminal, reasonClass);
    outbox.save(new EventOutboxEntity(c.getCaseId(), topic, c.getCaseId(), payload, now));
  }

  private PaymentContext toContext(CaseEntity c) {
    // Iter 4b.2 will populate agentMetadata (buyer.name/country, merchant.name/country, etc.)
    // during a CaseEnrichment step. Empty map for now keeps the Iter 3 stub Supervisor happy.
    return new PaymentContext(
        c.getCaseId(),
        c.getAgentId(),
        c.getMerchantId(),
        c.getAmount(),
        c.getCurrency(),
        null,
        java.util.Map.of());
  }

  public record StartResult(String caseId, SagaState state, boolean duplicate) {}
}
