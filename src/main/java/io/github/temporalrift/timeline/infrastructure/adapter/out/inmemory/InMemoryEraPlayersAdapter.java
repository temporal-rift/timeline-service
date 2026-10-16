package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;

@Component
@Profile("in-memory")
class InMemoryEraPlayersAdapter implements EraPlayersPort {

    private final Table<EraKey, List<UUID>> rosters;

    InMemoryEraPlayersAdapter(InMemoryDatabase database) {
        this.rosters = database.table(TableNames.ERA_PLAYERS);
    }

    /** One roster per era, like the unique {@code (game_id, era_number)} index: a second save fails. */
    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, List<UUID> playerIds) {
        rosters.insert(new EraKey(gameId, eraNumber), List.copyOf(playerIds));
    }

    @Override
    public Optional<List<UUID>> find(UUID gameId, int eraNumber) {
        return rosters.find(new EraKey(gameId, eraNumber));
    }

    @Override
    public Optional<Integer> findLatestEraNumber(UUID gameId) {
        return rosters.entries(roster -> true).stream()
                .map(Map.Entry::getKey)
                .filter(key -> key.gameId().equals(gameId))
                .map(EraKey::eraNumber)
                .max(Integer::compare);
    }

    private record EraKey(UUID gameId, int eraNumber) {}
}
