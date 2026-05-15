package com.agentpay.orchestrator.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventOutboxRepository extends JpaRepository<EventOutboxEntity, Long> {

  /**
   * Hits idx_event_outbox_unpublished (partial index on published_at IS NULL). Order by id keeps
   * publish order = insert order per case (case_id is the partition key).
   */
  @Query("select o from EventOutboxEntity o where o.publishedAt is null order by o.id asc")
  List<EventOutboxEntity> findUnpublished(Pageable pageable);
}
