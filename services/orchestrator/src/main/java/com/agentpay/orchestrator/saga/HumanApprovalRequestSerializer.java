package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.domain.Decision;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.shared.events.HumanApprovalRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.stereotype.Component;

/**
 * Serializes the {@link HumanApprovalRequest} outbox payload emitted when a Saga enters
 * SUSPENDED_FOR_REVIEW (FR-O-005). Parallel structure to {@link PaymentEventSerializer}.
 */
@Component
public class HumanApprovalRequestSerializer {

  private final Clock clock;

  public HumanApprovalRequestSerializer(Clock clock) {
    this.clock = clock;
  }

  public byte[] toBytes(CaseEntity c, Decision decision) {
    HumanApprovalRequest event =
        HumanApprovalRequest.newBuilder()
            .setCaseId(c.getCaseId())
            .setRiskScore(decision.riskScore())
            .setComplianceOutcome(decision.compliance().outcome().name())
            .setAgentId(c.getAgentId())
            .setMerchantId(c.getMerchantId())
            .setAmount(c.getAmount().toPlainString())
            .setCurrency(c.getCurrency())
            .setRequestedAt(clock.instant())
            .build();
    return serialize(event);
  }

  private static byte[] serialize(HumanApprovalRequest event) {
    DatumWriter<HumanApprovalRequest> writer =
        new SpecificDatumWriter<>(HumanApprovalRequest.class);
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
