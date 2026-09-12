package io.github.temporalrift.timeline.domain.eventstore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AggregateSnapshotTest {

    @Test
    void constructor_negativeSequenceNr_throws() {
        assertThatThrownBy(() -> new AggregateSnapshot(
                        UUID.randomUUID(), "WeaverChain", "{}", -1, Instant.parse("2026-09-12T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
