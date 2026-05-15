package com.agentpay.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SagaStateTest {

  @ParameterizedTest
  @EnumSource(
      value = SagaState.class,
      names = {"COMMITTED", "DECLINED", "COMPENSATED"})
  void terminalStatesAreTerminal(SagaState state) {
    assertThat(state.isTerminal()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = SagaState.class,
      names = {"COMMITTED", "DECLINED", "COMPENSATED"},
      mode = EnumSource.Mode.EXCLUDE)
  void nonTerminalStatesAreNotTerminal(SagaState state) {
    assertThat(state.isTerminal()).isFalse();
  }
}
