package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatusOrderBySeqAsc(OutboxStatus status);

    /**
     * Atomic conditional claim, mirroring this repo's other compare-and-set transitions
     * (processed_events insert-first claim, reconnect-saga status transitions): only the caller that
     * flips {@code expectedStatus} to {@code newStatus} may act on the row.
     *
     * <p>{@code @Transactional} lives here, not on {@code OutboxRelay}'s calling methods — those call
     * this repository method via {@code this.claim(...)}-style self-invocation from {@code relay()},
     * which bypasses Spring's proxy-based transaction advice entirely.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEventEntity e SET e.status = :newStatus " + "WHERE e.id = :id AND e.status = :expectedStatus")
    int compareAndSetStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") OutboxStatus expectedStatus,
            @Param("newStatus") OutboxStatus newStatus);
}
