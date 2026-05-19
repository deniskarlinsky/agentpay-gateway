package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Detached, immutable view of a {@link CaseEntity} captured inside an active JPA transaction. The
 * {@link PaymentSaga} drives transitions from snapshots so that JSONB fields (read while the
 * Hibernate session is open) are safely materialised before any network I/O runs — supervisor and
 * PSP calls must never happen inside {@code @Transactional}.
 */
record CaseSnapshot(
    String caseId,
    String agentId,
    String merchantId,
    BigDecimal amount,
    String currency,
    SagaState state,
    Map<String, String> agentMetadata,
    JsonNode decisionJsonb) {

  static CaseSnapshot from(CaseEntity c) {
    Map<String, String> meta = c.getAgentMetadataJsonb();
    JsonNode dec = c.getDecisionJsonb();
    return new CaseSnapshot(
        c.getCaseId(),
        c.getAgentId(),
        c.getMerchantId(),
        c.getAmount(),
        c.getCurrency(),
        c.getState(),
        meta == null ? Map.of() : Map.copyOf(meta),
        dec);
  }
}
