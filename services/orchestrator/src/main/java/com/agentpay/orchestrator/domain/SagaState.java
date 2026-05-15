package com.agentpay.orchestrator.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Saga lifecycle states (FR-O-001). Terminal states are {@link #COMMITTED}, {@link #DECLINED},
 * {@link #COMPENSATED}. {@link #SUSPENDED_FOR_REVIEW} is a recoverable pause exercised in Iter 4.
 */
public enum SagaState {
  INITIATED,
  HELD,
  REVIEWING,
  APPROVED,
  ROUTED,
  COMMITTED,
  DECLINED,
  COMPENSATED,
  SUSPENDED_FOR_REVIEW;

  private static final Set<SagaState> TERMINAL = EnumSet.of(COMMITTED, DECLINED, COMPENSATED);

  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }
}
