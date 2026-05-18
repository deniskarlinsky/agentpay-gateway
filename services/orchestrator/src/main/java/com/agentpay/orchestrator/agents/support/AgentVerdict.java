package com.agentpay.orchestrator.agents.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA mapping for the agent_verdicts table (REQUIREMENTS.md §8). */
@Entity
@Table(name = "agent_verdicts")
public class AgentVerdict {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "case_id", length = 64, nullable = false)
  private String caseId;

  @Column(name = "agent_name", length = 64, nullable = false)
  private String agentName;

  @Column(name = "model", length = 64, nullable = false)
  private String model;

  @Column(name = "verdict_json", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String verdictJson;

  @Column(name = "input_tokens", nullable = false)
  private int inputTokens;

  @Column(name = "output_tokens", nullable = false)
  private int outputTokens;

  @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
  private BigDecimal costUsd;

  @Column(name = "latency_ms", nullable = false)
  private int latencyMs;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected AgentVerdict() {}

  public AgentVerdict(
      String caseId,
      String agentName,
      String model,
      String verdictJson,
      int inputTokens,
      int outputTokens,
      BigDecimal costUsd,
      int latencyMs,
      OffsetDateTime createdAt) {
    this.caseId = caseId;
    this.agentName = agentName;
    this.model = model;
    this.verdictJson = verdictJson;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.costUsd = costUsd;
    this.latencyMs = latencyMs;
    this.createdAt = createdAt;
  }

  public Long getId() {
    return id;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getAgentName() {
    return agentName;
  }

  public String getModel() {
    return model;
  }

  public String getVerdictJson() {
    return verdictJson;
  }

  public int getInputTokens() {
    return inputTokens;
  }

  public int getOutputTokens() {
    return outputTokens;
  }

  public BigDecimal getCostUsd() {
    return costUsd;
  }

  public int getLatencyMs() {
    return latencyMs;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
