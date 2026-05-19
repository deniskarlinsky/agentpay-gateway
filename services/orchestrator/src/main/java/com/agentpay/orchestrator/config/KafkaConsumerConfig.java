package com.agentpay.orchestrator.config;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

/**
 * Kafka consumer wiring for the human-approval response listener (FR-O-005). Uses MANUAL_IMMEDIATE
 * ack so the offset is only committed after the saga handler has successfully persisted the
 * resulting state transition — a transient DB failure leaves the record uncommitted and Kafka
 * redelivers on the next poll. Idempotency is enforced inside the saga handlers.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

  private final String bootstrapServers;

  public KafkaConsumerConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    this.bootstrapServers = bootstrapServers;
  }

  @Bean
  ConsumerFactory<String, byte[]> humanApprovalResponseConsumerFactory(
      @Value("${agentpay.events.human-approval-responses-group-id}") String groupId) {
    Map<String, Object> props =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG,
            groupId,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            ByteArrayDeserializer.class,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false,
            ConsumerConfig.ISOLATION_LEVEL_CONFIG,
            "read_committed");
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]>
      humanApprovalResponseListenerContainerFactory(
          ConsumerFactory<String, byte[]> humanApprovalResponseConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(humanApprovalResponseConsumerFactory);
    factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
    return factory;
  }
}
