package com.agentpay.orchestrator.agents;

import com.agentpay.orchestrator.domain.PaymentContext;

/**
 * Renders a {@link PaymentContext} into the {@code key=value} line format the three specialist
 * prompts expect. Centralised here so the three agents stay in step on field order, separator
 * choice, and metadata-block handling.
 */
final class PaymentContextRenderer {

  private PaymentContextRenderer() {}

  static String render(PaymentContext ctx) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("caseId=").append(ctx.caseId()).append('\n');
    sb.append("agentId=").append(ctx.agentId()).append('\n');
    sb.append("merchantId=").append(ctx.merchantId()).append('\n');
    sb.append("amount=").append(ctx.amount().toPlainString()).append('\n');
    sb.append("currency=").append(ctx.currency()).append('\n');
    sb.append("description=").append(nullToEmpty(ctx.description())).append('\n');
    if (ctx.agentMetadata() != null) {
      ctx.agentMetadata()
          .forEach((k, v) -> sb.append(k).append('=').append(nullToEmpty(v)).append('\n'));
    }
    return sb.toString();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
