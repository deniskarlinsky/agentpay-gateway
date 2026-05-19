package com.agentpay.orchestrator.saga;

import com.agentpay.shared.events.HumanApprovalDecision;
import com.agentpay.shared.events.HumanApprovalResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes human.approval.responses (FR-O-005). Manual acknowledgement: the offset is committed
 * only after {@link PaymentSaga#onApprovalGranted} or {@link PaymentSaga#onApprovalDenied} returns
 * normally, so a transient failure (DB unavailable, etc.) leaves the record uncommitted and Kafka
 * redelivers on the next poll. Idempotency for redelivery is enforced inside the saga handlers.
 */
@Component
public class HumanApprovalResponseListener {

  private static final Logger log = LoggerFactory.getLogger(HumanApprovalResponseListener.class);

  private final PaymentSaga saga;

  public HumanApprovalResponseListener(PaymentSaga saga) {
    this.saga = saga;
  }

  @KafkaListener(
      topics = "${agentpay.events.human-approval-responses-topic}",
      groupId = "${agentpay.events.human-approval-responses-group-id}",
      containerFactory = "humanApprovalResponseListenerContainerFactory")
  public void onMessage(byte[] payload, Acknowledgment ack) {
    HumanApprovalResponse response = decode(payload);
    String caseId = response.getCaseId().toString();
    HumanApprovalDecision decision = response.getDecision();
    log.info(
        "human approval response received case_id={} decision={} decided_by={}",
        caseId,
        decision,
        response.getDecidedBy());
    switch (decision) {
      case GRANTED -> saga.onApprovalGranted(caseId);
      case DENIED ->
          saga.onApprovalDenied(
              caseId, response.getReason() == null ? null : response.getReason().toString());
    }
    ack.acknowledge();
  }

  private static HumanApprovalResponse decode(byte[] bytes) {
    DatumReader<HumanApprovalResponse> reader =
        new SpecificDatumReader<>(HumanApprovalResponse.class);
    BinaryDecoder decoder =
        DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(bytes), null);
    try {
      return reader.read(null, decoder);
    } catch (IOException e) {
      throw new IllegalStateException("failed to decode HumanApprovalResponse", e);
    }
  }
}
