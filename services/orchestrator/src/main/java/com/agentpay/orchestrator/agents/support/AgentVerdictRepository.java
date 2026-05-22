package com.agentpay.orchestrator.agents.support;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentVerdictRepository extends JpaRepository<AgentVerdict, Long> {

  /**
   * Sum of per-verdict cost rows for one case (NFR-O-003). Returns {@code BigDecimal.ZERO} when no
   * verdicts have been recorded — JPQL {@code COALESCE} keeps the call total-cost monotonic so
   * PaymentSaga can write {@code cases.cost_usd} on every terminal without a separate null guard.
   */
  @Query("select coalesce(sum(v.costUsd), 0) from AgentVerdict v where v.caseId = :caseId")
  BigDecimal sumCostByCaseId(@Param("caseId") String caseId);

  List<AgentVerdict> findByCaseIdOrderByCreatedAtAsc(String caseId);
}
