package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.shared.events.BudgetExceededEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.stereotype.Component;

/**
 * Avro serializer for the {@code case.budget_exceeded} outbox row (NFR-COST-001, Scenario F).
 * Mirrors {@link PaymentEventSerializer}'s shape so downstream consumers can deserialize via the
 * same generated Avro classes from {@code shared:api-contracts}.
 */
@Component
public class BudgetExceededSerializer {

  private final Clock clock;

  public BudgetExceededSerializer(Clock clock) {
    this.clock = clock;
  }

  public byte[] toBytes(CaseEntity c, BigDecimal runningCostUsd, BigDecimal budgetUsd) {
    BudgetExceededEvent event =
        BudgetExceededEvent.newBuilder()
            .setCaseId(c.getCaseId())
            .setAgentId(c.getAgentId())
            .setMerchantId(c.getMerchantId())
            .setRunningCostUsd(runningCostUsd.toPlainString())
            .setBudgetUsd(budgetUsd.toPlainString())
            .setOccurredAt(clock.instant())
            .build();
    return serialize(event);
  }

  private static byte[] serialize(BudgetExceededEvent event) {
    DatumWriter<BudgetExceededEvent> writer = new SpecificDatumWriter<>(BudgetExceededEvent.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
    try {
      writer.write(event, encoder);
      encoder.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
