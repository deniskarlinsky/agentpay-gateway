package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.observability.CaseObservation;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.psp.MockPspClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 *
 * <p>All @Transactional persistence steps live on {@link SagaPersistence}; calls cross the Spring
 * AOP proxy boundary so the annotations take effect (Iter 6 refactor: replaced the @Lazy
 * self-injection that worked around this).
 */
@Service
public class PaymentSaga {

  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);

  private final CaseRepository cases;
  private final Supervisor supervisor;
  private final MockPspClient psp;
  private final CaseObservation caseObservation;
  private final ObjectMapper objectMapper;
  private final SagaPersistence persistence;

  public PaymentSaga(
      CaseRepository cases,
      Supervisor supervisor,
      MockPspClient psp,
      CaseObservation caseObservation,
      ObjectMapper objectMapper,
      SagaPersistence persistence) {
    this.cases = cases;
    this.supervisor = supervisor;
    this.psp = psp;
    this.caseObservation = caseObservation;
    this.objectMapper = objectMapper;
    this.persistence = persistence;
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
          persistence.createInitiated(ctx, intentTokenJti);
          driveForward(ctx.caseId());
          persistence.finalizeCost(ctx.caseId());
          SagaPersistence.TerminalView view = persistence.readTerminalView(ctx.caseId());
          return new CaseObservation.Outcome<>(
              new StartResult(ctx.caseId(), view.state(), false),
              view.state().name(),
              view.costUsd());
        });
  }

  /** Resume entry point for SagaRecoveryRunner (NFR-R-002) and human-approval handlers. */
  public void driveForward(String caseId) {
    while (true) {
      CaseSnapshot snap = persistence.loadSnapshot(caseId);
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
    var snap = persistence.loadSnapshot(caseId);
    caseObservation.observe(
        caseId,
        snap.agentId(),
        snap.merchantId(),
        () -> {
          persistence.markApprovalGranted(caseId);
          driveForward(caseId);
          persistence.finalizeCost(caseId);
          SagaPersistence.TerminalView view = persistence.readTerminalView(caseId);
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
    var snap = persistence.loadSnapshot(caseId);
    caseObservation.observe(
        caseId,
        snap.agentId(),
        snap.merchantId(),
        () -> {
          persistence.markApprovalDenied(caseId, reason);
          persistence.finalizeCost(caseId);
          SagaPersistence.TerminalView view = persistence.readTerminalView(caseId);
          return new CaseObservation.Outcome<Void>(null, view.state().name(), view.costUsd());
        });
  }

  private void stepOnce(CaseSnapshot snap) {
    switch (snap.state()) {
      case INITIATED ->
          persistence.recordTransition(
              snap.caseId(), SagaState.INITIATED, SagaState.HELD, "funds held");
      case HELD ->
          persistence.recordTransition(
              snap.caseId(), SagaState.HELD, SagaState.REVIEWING, "decision plane invoked");
      case REVIEWING -> applyDecision(snap);
      case APPROVED ->
          persistence.recordTransition(
              snap.caseId(), SagaState.APPROVED, SagaState.ROUTED, "route selected");
      case ROUTED -> commitOrCompensate(snap);
      default -> throw new IllegalStateException("non-driveable state: " + snap.state());
    }
  }

  private void applyDecision(CaseSnapshot snap) {
    Decision d = supervisor.decide(toContext(snap));
    persistence.applyDecisionAtomic(snap.caseId(), d);
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
      persistence.recordTerminal(snap.caseId(), SagaState.ROUTED, SagaState.COMMITTED, null);
    } else {
      // FR-O-006: PSP failure → COMPENSATED. Reason class carries the ISO 20022 code.
      persistence.recordTerminal(
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
