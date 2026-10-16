package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

public interface EventStorePortContract extends TransactionalContract {

    EventStorePort eventStore();

    @Test
    @DisplayName("event store: a stream reads back in sequence order, whatever order it was appended in")
    default void eventStore_streamReadsBackInSequenceOrder() {
        var aggregateId = UUID.randomUUID();
        eventStore().append(storedEvent(aggregateId, 1));
        eventStore().append(storedEvent(aggregateId, 0));
        eventStore().append(storedEvent(aggregateId, 2));
        eventStore().append(storedEvent(UUID.randomUUID(), 0));

        assertThat(eventStore().readStream(aggregateId))
                .extracting(StoredEvent::sequenceNr)
                .containsExactly(0L, 1L, 2L);
        assertThat(eventStore().readStreamFrom(aggregateId, 1))
                .extracting(StoredEvent::sequenceNr)
                .containsExactly(1L, 2L);
        assertThat(eventStore().readStreamFrom(aggregateId, 3)).isEmpty();
        assertThat(eventStore().streamSize(aggregateId)).isEqualTo(3);
        assertThat(eventStore().streamSize(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("event store: an append at an occupied position fails and leaves the stored event unchanged")
    default void eventStore_occupiedPositionFails() {
        var aggregateId = UUID.randomUUID();
        var original = storedEvent(aggregateId, 0);
        eventStore().append(original);

        assertThatThrownBy(() -> eventStore().append(storedEvent(aggregateId, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(eventStore().readStream(aggregateId)).containsExactly(original);
    }

    @Test
    @DisplayName("event store: events appended by a transaction that then throws are absent afterwards")
    default void eventStore_rolledBackAppendsLeaveNoTrace() {
        var aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> transactions().executeWithoutResult(status -> {
                    eventStore().append(storedEvent(aggregateId, 0));
                    eventStore().append(storedEvent(aggregateId, 1));
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");

        assertThat(eventStore().readStream(aggregateId)).isEmpty();
        assertThat(eventStore().streamSize(aggregateId)).isZero();
    }

    @Test
    @DisplayName("event store: another transaction sees an append only once its transaction commits")
    default void eventStore_appendIsVisibleOnlyAfterCommit() {
        var aggregateId = UUID.randomUUID();

        var held = holdTransaction(() -> {
            eventStore().append(storedEvent(aggregateId, 0));
            return eventStore().streamSize(aggregateId);
        });

        assertThat(eventStore().streamSize(aggregateId)).isZero();
        assertThat(held.commit()).isEqualTo(1);
        assertThat(eventStore().streamSize(aggregateId)).isEqualTo(1);
    }

    private static StoredEvent storedEvent(UUID aggregateId, long sequenceNr) {
        return new StoredEvent(
                UUID.randomUUID(),
                aggregateId,
                "WeaverChain",
                "WeaverChainStarted",
                1,
                "{}",
                Instant.parse("2026-03-01T10:00:00Z"),
                sequenceNr);
    }
}
