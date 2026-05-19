package com.agentpay.orchestrator.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentpay.orchestrator.persistence.EventOutboxEntity;
import com.agentpay.orchestrator.persistence.EventOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxPublisherTest {

  @Test
  void drainPublishesAndMarksRows() {
    EventOutboxRepository outbox = mock(EventOutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    KafkaOperations<String, byte[]> ops = mock(KafkaOperations.class);
    when(kafkaTemplate.executeInTransaction(any()))
        .thenAnswer(
            inv -> {
              KafkaOperations.OperationsCallback<String, byte[], Object> cb = inv.getArgument(0);
              return cb.doInOperations(ops);
            });

    Clock clock = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
    EventOutboxEntity row1 =
        new EventOutboxEntity(
            "case-1", "payment.events", "case-1", new byte[] {1}, OffsetDateTime.now(clock));
    EventOutboxEntity row2 =
        new EventOutboxEntity(
            "case-2", "payment.events", "case-2", new byte[] {2}, OffsetDateTime.now(clock));
    when(outbox.findUnpublished(any(Pageable.class))).thenReturn(List.of(row1, row2));

    OutboxPublisher publisher = new OutboxPublisher(outbox, kafkaTemplate, clock, 200L, null);
    publisher.drain();

    verify(ops, times(1)).send("payment.events", "case-1", new byte[] {1});
    verify(ops, times(1)).send("payment.events", "case-2", new byte[] {2});
    assertThat(row1.getPublishedAt()).isNotNull();
    assertThat(row2.getPublishedAt()).isNotNull();
  }

  @Test
  void emptyBatchSkipsKafkaCall() {
    EventOutboxRepository outbox = mock(EventOutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
    when(outbox.findUnpublished(any(Pageable.class))).thenReturn(List.of());

    new OutboxPublisher(outbox, kafkaTemplate, Clock.systemUTC(), 200L, null).drain();

    verify(kafkaTemplate, never()).executeInTransaction(any());
  }
}
