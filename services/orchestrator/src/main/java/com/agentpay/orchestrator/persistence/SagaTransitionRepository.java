package com.agentpay.orchestrator.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaTransitionRepository extends JpaRepository<SagaTransitionEntity, Long> {

  List<SagaTransitionEntity> findByCaseIdOrderByCreatedAtAsc(String caseId);
}
