package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains rows from event_outbox to Kafka using the transactional producer (NFR-R-003 via outbox
 * pattern, see B-4). Each poll is one Kafka transaction containing all pending rows; rows that fail
 * to publish stay unmarked and the next poll retries — at-least-once delivery, idempotent consumers
 * required (PaymentEvent terminal events are keyed by case_id, so duplicates are safe).
 *
 * <p>Lifecycle (Iter 6): self-owned {@link ScheduledExecutorService} replaces {@code @Scheduled}.
 * Spring's default {@code TaskScheduler} fires on the {@code taskScheduler} bean which the
 * container shuts down AFTER {@code dataSource} and {@code entityManagerFactory}, producing
 * PSQLException("This connection has been closed") on every app shutdown when an in-flight drain
 * tries to read pending rows. Owning the executor ourselves with a {@link PreDestroy} that fires
 * BEFORE JPA close gives a clean shutdown.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final int BATCH_SIZE = 100;

  private final EventOutboxRepository outbox;
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final Clock clock;
  private final long pollIntervalMs;
  private final OutboxPublisher self;
  private final ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> task;

  public OutboxPublisher(
      EventOutboxRepository outbox,
      KafkaTemplate<String, byte[]> kafkaTemplate,
      Clock clock,
      @Value("${agentpay.outbox.poll-interval-ms}") long pollIntervalMs,
      @org.springframework.context.annotation.Lazy OutboxPublisher self) {
    this.outbox = outbox;
    this.kafkaTemplate = kafkaTemplate;
    this.clock = clock;
    this.pollIntervalMs = pollIntervalMs;
    this.self = self;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "outbox-publisher");
              t.setDaemon(true);
              return t;
            });
  }

  @PostConstruct
  void start() {
    task =
        scheduler.scheduleWithFixedDelay(
            this::drainQuietly, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Cancels the schedule, waits up to 5s for an in-flight drain to finish, then shuts down the
   * executor. Runs in the Spring bean-destruction phase BEFORE the datasource closes; a slow
   * in-flight drain that exceeds the 5s grace gets a hard {@code shutdownNow}, which is fine
   * because the next startup re-reads any unpublished rows from {@code event_outbox}.
   */
  @PreDestroy
  void shutdown() {
    if (task != null) {
      task.cancel(false);
    }
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      scheduler.shutdownNow();
    }
  }

  /**
   * Catches RuntimeException so a single failed drain does not cancel the schedule ({@code
   * ScheduledExecutorService} suppresses further executions on an unchecked throw).
   */
  void drainQuietly() {
    try {
      self.drain();
    } catch (RuntimeException e) {
      log.warn("outbox drain failed; will retry on next tick", e);
    }
  }

  // Qualify: spring-kafka's auto-configured kafkaTransactionManager is also a TransactionManager
  // bean, so an unqualified @Transactional can't resolve. The drain wraps JPA reads + the
  // markPublished writes; the Kafka send is its own transaction via
  // kafkaTemplate.executeInTransaction.
  @Transactional("transactionManager")
  public void drain() {
    List<EventOutboxEntity> batch = outbox.findUnpublished(PageRequest.of(0, BATCH_SIZE));
    if (batch.isEmpty()) {
      return;
    }
    kafkaTemplate.executeInTransaction(
        ops -> {
          for (EventOutboxEntity row : batch) {
            ops.send(row.getTopic(), row.getPartitionKey(), row.getPayload());
          }
          return null;
        });
    OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    for (EventOutboxEntity row : batch) {
      row.markPublished(now);
    }
    log.debug("outbox published rows={}", batch.size());
  }
}
