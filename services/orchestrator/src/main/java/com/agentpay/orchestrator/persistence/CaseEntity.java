package com.agentpay.orchestrator.persistence;

import com.agentpay.orchestrator.domain.SagaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cases")
public class CaseEntity {

  @Id
  @Column(name = "case_id", length = 64)
  private String caseId;

  @Column(name = "agent_id", length = 64, nullable = false)
  private String agentId;

  @Column(name = "merchant_id", length = 64, nullable = false)
  private String merchantId;

  @Column(name = "amount", nullable = false, precision = 18, scale = 4)
  private BigDecimal amount;

  // V1__init.sql declares CHAR(3) per REQUIREMENTS.md §8; tell Hibernate to validate as CHAR
  // rather than its default VARCHAR or the validator will reject the column type.
  @Column(name = "currency", nullable = false, length = 3)
  @JdbcTypeCode(SqlTypes.CHAR)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private SagaState state;

  @Column(name = "intent_token_jti", nullable = false)
  private UUID intentTokenJti;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "cost_usd", precision = 10, scale = 6)
  private BigDecimal costUsd;

  protected CaseEntity() {}

  public CaseEntity(
      String caseId,
      String agentId,
      String merchantId,
      BigDecimal amount,
      String currency,
      SagaState state,
      UUID intentTokenJti,
      OffsetDateTime now) {
    this.caseId = caseId;
    this.agentId = agentId;
    this.merchantId = merchantId;
    this.amount = amount;
    this.currency = currency;
    this.state = state;
    this.intentTokenJti = intentTokenJti;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getAgentId() {
    return agentId;
  }

  public String getMerchantId() {
    return merchantId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public SagaState getState() {
    return state;
  }

  public UUID getIntentTokenJti() {
    return intentTokenJti;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public BigDecimal getCostUsd() {
    return costUsd;
  }

  public void setState(SagaState state, OffsetDateTime now) {
    this.state = state;
    this.updatedAt = now;
  }

  public void setCostUsd(BigDecimal costUsd) {
    this.costUsd = costUsd;
  }
}
