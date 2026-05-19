package com.agentpay.orchestrator.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentpay.orchestrator.decision.Supervisor;
import com.agentpay.orchestrator.domain.ComplianceVerdict;
import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.domain.Outcome;
import com.agentpay.orchestrator.domain.PaymentContext;
import com.agentpay.orchestrator.domain.RouteRecommendation;
import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import com.agentpay.orchestrator.persistence.SagaTransitionEntity;
import com.agentpay.orchestrator.persistence.SagaTransitionRepository;
import com.agentpay.orchestrator.psp.MockPspClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PaymentSagaUnitTest {

  private CaseRepository cases;
  private SagaTransitionRepository transitions;
  private EventOutboxRepository outbox;
  private Supervisor supervisor;
  private MockPspClient psp;
  private PaymentEventSerializer serializer;
  private PaymentSaga saga;

  /** Tracks the in-memory state of the CaseEntity across mocked save/find calls. */
  private CaseEntity stored;

  private final List<SagaTransitionEntity> transitionRows = new ArrayList<>();
  private final List<EventOutboxEntity> outboxRows = new ArrayList<>();

  @BeforeEach
  void setUp() {
    cases = Mockito.mock(CaseRepository.class);
    transitions = Mockito.mock(SagaTransitionRepository.class);
    outbox = Mockito.mock(EventOutboxRepository.class);
    supervisor = Mockito.mock(Supervisor.class);
    psp = Mockito.mock(MockPspClient.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
    serializer = new PaymentEventSerializer(clock);
    HumanApprovalRequestSerializer approvalRequestSerializer =
        new HumanApprovalRequestSerializer(clock);
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
    saga =
        new PaymentSaga(
            cases,
            transitions,
            outbox,
            supervisor,
            psp,
            serializer,
            approvalRequestSerializer,
            objectMapper,
            clock,
            "payment.events",
            "human.approval.requests");
    // In production Spring injects @Lazy self with the AOP proxy; in this unit test the
    // @Transactional semantics are irrelevant (repos are mocked), so direct self-reference is
    // sufficient to satisfy the self.xxx() invocations inside start()/stepOnce().
    saga.setSelf(saga);

    when(cases.save(any(CaseEntity.class)))
        .thenAnswer(
            inv -> {
              stored = inv.getArgument(0);
              return stored;
            });
    when(cases.findById(any()))
        .thenAnswer(inv -> stored == null ? Optional.empty() : Optional.of(stored));
    when(transitions.save(any(SagaTransitionEntity.class)))
        .thenAnswer(
            inv -> {
              transitionRows.add(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(outbox.save(any(EventOutboxEntity.class)))
        .thenAnswer(
            inv -> {
              outboxRows.add(inv.getArgument(0));
              return inv.getArgument(0);
            });
    lenient()
        .when(supervisor.decide(any()))
        .thenReturn(
            Decision.approved(
                0,
                new ComplianceVerdict(Outcome.PASS, List.of(), "stub: clear"),
                new RouteRecommendation("psp-c", "route-1", 0.95f, 30, "stub"),
                List.of("stub")));
    lenient()
        .when(psp.charge(any()))
        .thenReturn(new MockPspClient.ChargeResponse("case-1", "psp-c", true, "AUTH-1", null, 45));
  }

  @Test
  void happyPathReachesCommitted() {
    saga.start(ctx("case-1"), UUID.randomUUID());

    assertThat(stored.getState()).isEqualTo(SagaState.COMMITTED);
    // INITIATED → HELD → REVIEWING → APPROVED → ROUTED → COMMITTED  (the initial INITIATED
    // insertion is also a transition row; total 6.)
    assertThat(transitionRows).hasSize(6);
    assertThat(transitionRows.stream().map(SagaTransitionEntity::getStateTo).toList())
        .containsExactly(
            SagaState.INITIATED,
            SagaState.HELD,
            SagaState.REVIEWING,
            SagaState.APPROVED,
            SagaState.ROUTED,
            SagaState.COMMITTED);
    // Exactly one outbox row on the terminal transition (FR-O-009).
    assertThat(outboxRows).hasSize(1);
    assertThat(outboxRows.get(0).getTopic()).isEqualTo("payment.events");
    assertThat(outboxRows.get(0).getPartitionKey()).isEqualTo("case-1");
  }

  @Test
  void pspFailureCompensatesAndPublishes() {
    when(psp.charge(any()))
        .thenReturn(new MockPspClient.ChargeResponse("case-2", "psp-c", false, null, "AC01", 0));

    saga.start(ctx("case-2"), UUID.randomUUID());

    assertThat(stored.getState()).isEqualTo(SagaState.COMPENSATED);
    assertThat(outboxRows).hasSize(1);
    ArgumentCaptor<SagaTransitionEntity> captor =
        ArgumentCaptor.forClass(SagaTransitionEntity.class);
    verify(transitions, atLeastOnce()).save(captor.capture());
    SagaTransitionEntity terminal = captor.getAllValues().get(captor.getAllValues().size() - 1);
    assertThat(terminal.getStateTo()).isEqualTo(SagaState.COMPENSATED);
    assertThat(terminal.getReason()).isEqualTo("AC01");
  }

  @Test
  void duplicateStartIsIdempotent() {
    saga.start(ctx("case-3"), UUID.randomUUID());
    int rowsAfterFirst = transitionRows.size();
    int outboxAfterFirst = outboxRows.size();

    PaymentSaga.StartResult second = saga.start(ctx("case-3"), UUID.randomUUID());

    assertThat(second.duplicate()).isTrue();
    assertThat(second.state()).isEqualTo(SagaState.COMMITTED);
    // No new transitions or outbox rows on the duplicate call.
    assertThat(transitionRows).hasSize(rowsAfterFirst);
    assertThat(outboxRows).hasSize(outboxAfterFirst);
    // Iter 4b.2 added a per-case decision cache: supervisor.decide is called exactly once on the
    // REVIEWING step; commitOrCompensate reuses the cached Decision instead of re-calling.
    // Duplicate start short-circuits at the findById guard → still 1 invocation total.
    verify(supervisor, times(1)).decide(any());
  }

  @Test
  void recoveryResumesFromHeldToCommitted() {
    // Seed a case already in HELD state, no Spring context — directly invoke driveForward.
    stored =
        new CaseEntity(
            "case-recover",
            "agent-1",
            "merchant-1",
            new BigDecimal("42.50"),
            "USD",
            SagaState.HELD,
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC));

    saga.driveForward("case-recover");

    assertThat(stored.getState()).isEqualTo(SagaState.COMMITTED);
    assertThat(outboxRows).hasSize(1);
    // Transitions recorded since recovery start: HELD→REVIEWING, REVIEWING→APPROVED,
    // APPROVED→ROUTED, ROUTED→COMMITTED. INITIATED row predates recovery.
    assertThat(transitionRows.stream().map(SagaTransitionEntity::getStateTo).toList())
        .containsExactly(
            SagaState.REVIEWING, SagaState.APPROVED, SagaState.ROUTED, SagaState.COMMITTED);
  }

  @Test
  void terminalStateShortCircuitsDriveForward() {
    stored =
        new CaseEntity(
            "case-terminal",
            "agent-1",
            "merchant-1",
            new BigDecimal("42.50"),
            "USD",
            SagaState.COMMITTED,
            UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC));

    saga.driveForward("case-terminal");

    verify(supervisor, never()).decide(any());
    verify(psp, never()).charge(any());
    assertThat(transitionRows).isEmpty();
  }

  private PaymentContext ctx(String caseId) {
    return new PaymentContext(
        caseId, "agent-1", "merchant-1", new BigDecimal("42.50"), "USD", "test", Map.of());
  }
}
