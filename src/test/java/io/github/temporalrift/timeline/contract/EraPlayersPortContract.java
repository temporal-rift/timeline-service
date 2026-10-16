package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;

public interface EraPlayersPortContract {

    EraPlayersPort eraPlayers();

    @Test
    @DisplayName("era players: a roster reads back per era, and an empty roster differs from none at all")
    default void eraPlayers_rosterReadsBackPerEra() {
        var gameId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID());

        assertThat(eraPlayers().find(gameId, 1)).isEmpty();
        eraPlayers().save(gameId, 1, players);
        eraPlayers().save(gameId, 2, List.of());

        assertThat(eraPlayers().find(gameId, 1)).contains(players);
        assertThat(eraPlayers().find(gameId, 2)).contains(List.of());
        assertThat(eraPlayers().find(gameId, 3)).isEmpty();
        assertThat(eraPlayers().find(UUID.randomUUID(), 1)).isEmpty();
    }

    @Test
    @DisplayName("era players: the latest era number is the highest recorded for the game")
    default void eraPlayers_latestEraIsTheHighest() {
        var gameId = UUID.randomUUID();
        assertThat(eraPlayers().findLatestEraNumber(gameId)).isEmpty();

        eraPlayers().save(gameId, 2, List.of());
        eraPlayers().save(gameId, 1, List.of());
        eraPlayers().save(UUID.randomUUID(), 9, List.of());

        assertThat(eraPlayers().findLatestEraNumber(gameId)).contains(2);
    }

    @Test
    @DisplayName("era players: a second roster for the same era is refused")
    default void eraPlayers_secondRosterForAnEraIsRefused() {
        var gameId = UUID.randomUUID();
        var original = List.of(UUID.randomUUID());
        eraPlayers().save(gameId, 1, original);

        var other = List.of(UUID.randomUUID());

        assertThatThrownBy(() -> eraPlayers().save(gameId, 1, other))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(eraPlayers().find(gameId, 1)).contains(original);
    }
}
