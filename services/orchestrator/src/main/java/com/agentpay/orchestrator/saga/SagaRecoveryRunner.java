package com.agentpay.orchestrator.saga;

import com.agentpay.orchestrator.persistence.CaseEntity;
import com.agentpay.orchestrator.persistence.CaseRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * NFR-R-002 / FR-O-002: on startup, scan cases in non-terminal states and resume each. The partial
 * index idx_cases_non_terminal_state keeps this query cheap.
 */
@Component
public class SagaRecoveryRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SagaRecoveryRunner.class);

  private final CaseRepository cases;
  private final PaymentSaga saga;

  public SagaRecoveryRunner(CaseRepository cases, PaymentSaga saga) {
    this.cases = cases;
    this.saga = saga;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<CaseEntity> orphans = cases.findNonTerminal();
    if (orphans.isEmpty()) {
      return;
    }
    log.info("recovering non-terminal cases count={}", orphans.size());
    for (CaseEntity c : orphans) {
      try {
        saga.driveForward(c.getCaseId());
      } catch (RuntimeException e) {
        // One failed recovery must not block others (idempotent retry on next startup).
        log.warn(
            "recovery failed case_id={} state={} error={}",
            c.getCaseId(),
            c.getState(),
            e.toString());
      }
    }
  }
}
