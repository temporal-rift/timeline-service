package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;

public interface FutureEventEraIndexPortContract {

    FutureEventEraIndexPort eraIndex();

    @Test
    @DisplayName("era index: events of a game and era come back ordered by reveal index")
    default void eraIndex_ordersByRevealIndexWithinGameAndEra() {
        var gameId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();
        eraIndex().add(second, gameId, 1, 1);
        eraIndex().add(third, gameId, 1, 2);
        eraIndex().add(first, gameId, 1, 0);
        eraIndex().add(UUID.randomUUID(), gameId, 2, 0);
        eraIndex().add(UUID.randomUUID(), UUID.randomUUID(), 1, 0);

        assertThat(eraIndex().findByGameIdAndEraNumber(gameId, 1))
                .containsExactly(
                        new IndexedEventId(first, 0), new IndexedEventId(second, 1), new IndexedEventId(third, 2));
        assertThat(eraIndex().findByGameIdAndEraNumber(gameId, 3)).isEmpty();
    }

    @Test
    @DisplayName("era index: indexing an event again replaces its entry")
    default void eraIndex_reindexingAnEventReplacesItsEntry() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        eraIndex().add(eventId, gameId, 1, 0);

        eraIndex().add(eventId, gameId, 1, 4);

        assertThat(eraIndex().findByGameIdAndEraNumber(gameId, 1)).containsExactly(new IndexedEventId(eventId, 4));
    }
}
