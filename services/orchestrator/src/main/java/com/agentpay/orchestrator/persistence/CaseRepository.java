package com.agentpay.orchestrator.persistence;

import com.agentpay.orchestrator.domain.SagaState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {

  /**
   * Crash-recovery scan (NFR-R-002). The partial index idx_cases_non_terminal_state matches this
   * predicate.
   */
  @Query(
      """
      select c from CaseEntity c
      where c.state not in (
        com.agentpay.orchestrator.domain.SagaState.COMMITTED,
        com.agentpay.orchestrator.domain.SagaState.DECLINED,
        com.agentpay.orchestrator.domain.SagaState.COMPENSATED
      )
      """)
  List<CaseEntity> findNonTerminal();

  default boolean isTerminal(SagaState state) {
    return state == SagaState.COMMITTED
        || state == SagaState.DECLINED
        || state == SagaState.COMPENSATED;
  }
}
