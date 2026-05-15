package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.domain.SagaState;
import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.shared.events.PaymentEvent;
import com.agentpay.shared.events.PaymentEventType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventSerializer {

  private final Clock clock;

  public PaymentEventSerializer(Clock clock) {
    this.clock = clock;
  }

  public byte[] toBytes(CaseEntity c, SagaState terminalState, String reasonClass) {
    PaymentEvent event =
        PaymentEvent.newBuilder()
            .setEventType(toEventType(terminalState))
            .setCaseId(c.getCaseId())
            .setAgentId(c.getAgentId())
            .setMerchantId(c.getMerchantId())
            .setAmount(c.getAmount().toPlainString())
            .setCurrency(c.getCurrency())
            .setTerminalState(terminalState.name())
            .setReasonClass(reasonClass)
            .setOccurredAt(clock.instant())
            .build();
    return serialize(event);
  }

  static PaymentEventType toEventType(SagaState terminal) {
    return switch (terminal) {
      case COMMITTED -> PaymentEventType.COMPLETED;
      case DECLINED -> PaymentEventType.DECLINED;
      case COMPENSATED -> PaymentEventType.COMPENSATED;
      default ->
          throw new IllegalArgumentException(
              "non-terminal state cannot produce a PaymentEvent: " + terminal);
    };
  }

  private static byte[] serialize(PaymentEvent event) {
    DatumWriter<PaymentEvent> writer = new SpecificDatumWriter<>(PaymentEvent.class);
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
