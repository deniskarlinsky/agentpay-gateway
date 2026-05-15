package com.agentpay.orchestrator.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "event_outbox")
public class EventOutboxEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "case_id", length = 64, nullable = false)
  private String caseId;

  @Column(name = "topic", length = 128, nullable = false)
  private String topic;

  @Column(name = "partition_key", length = 128, nullable = false)
  private String partitionKey;

  @Column(name = "payload", nullable = false)
  private byte[] payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  protected EventOutboxEntity() {}

  public EventOutboxEntity(
      String caseId, String topic, String partitionKey, byte[] payload, OffsetDateTime now) {
    this.caseId = caseId;
    this.topic = topic;
    this.partitionKey = partitionKey;
    this.payload = payload;
    this.createdAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getCaseId() {
    return caseId;
  }

  public String getTopic() {
    return topic;
  }

  public String getPartitionKey() {
    return partitionKey;
  }

  public byte[] getPayload() {
    return payload;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getPublishedAt() {
    return publishedAt;
  }

  public void markPublished(OffsetDateTime now) {
    this.publishedAt = now;
  }
}
