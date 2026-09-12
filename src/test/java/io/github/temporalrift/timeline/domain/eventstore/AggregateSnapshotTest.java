package io.github.temporalrift.timeline.domain.eventstore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AggregateSnapshotTest {

    @Test
    void constructor_negativeSequenceNr_throws() {
        var chainId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-09-12T00:00:00Z");

        assertThatThrownBy(() -> new AggregateSnapshot(chainId, "WeaverChain", "{}", -1, createdAt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
