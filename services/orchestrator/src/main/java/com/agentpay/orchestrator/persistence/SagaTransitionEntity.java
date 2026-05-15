package com.agentpay.orchestrator.persistence;

import com.agentpay.orchestrator.domain.SagaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "saga_transitions")
public class SagaTransitionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "case_id", length = 64, nullable = false)
  private String caseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state_from", length = 32)
  private SagaState stateFrom;

  @Enumerated(EnumType.STRING)
  @Column(name = "state_to", length = 32, nullable = false)
  private SagaState stateTo;

  @Column(name = "reason")
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected SagaTransitionEntity() {}

  public SagaTransitionEntity(
      String caseId, SagaState stateFrom, SagaState stateTo, String reason, OffsetDateTime now) {
    this.caseId = caseId;
    this.stateFrom = stateFrom;
    this.stateTo = stateTo;
    this.reason = reason;
    this.createdAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getCaseId() {
    return caseId;
  }

  public SagaState getStateFrom() {
    return stateFrom;
  }

  public SagaState getStateTo() {
    return stateTo;
  }

  public String getReason() {
    return reason;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
