package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaEventStoreAdapter.class})
class JpaEventStoreAdapterTest {

    @Autowired
    EventStorePort eventStore;

    @Test
    void streamSize_countsRowsWithoutLoadingThem() {
        var aggregateId = UUID.randomUUID();
        eventStore.append(stored(aggregateId, 0));
        eventStore.append(stored(aggregateId, 1));
        eventStore.append(stored(aggregateId, 2));

        assertThat(eventStore.streamSize(aggregateId)).isEqualTo(3);
        assertThat(eventStore.streamSize(UUID.randomUUID())).isZero();
    }

    @Test
    void readStreamFrom_returnsOnlyTailFromSequence() {
        var aggregateId = UUID.randomUUID();
        eventStore.append(stored(aggregateId, 0));
        eventStore.append(stored(aggregateId, 1));
        eventStore.append(stored(aggregateId, 2));

        assertThat(eventStore.readStreamFrom(aggregateId, 0)).hasSize(3);
        assertThat(eventStore.readStreamFrom(aggregateId, 2))
                .extracting(StoredEvent::sequenceNr)
                .containsExactly(2L);
        assertThat(eventStore.readStreamFrom(aggregateId, 3)).isEmpty();
    }

    private static StoredEvent stored(UUID aggregateId, long sequenceNr) {
        return new StoredEvent(
                UUID.randomUUID(),
                aggregateId,
                "WeaverChain",
                "WeaverChainStarted",
                1,
                "{}",
                Instant.parse("2026-09-12T00:00:00Z"),
                sequenceNr);
    }
}
