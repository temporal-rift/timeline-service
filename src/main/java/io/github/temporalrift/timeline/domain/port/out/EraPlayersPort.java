package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for the player roster {@code EraStarted} carries (event-schema.md §3.2) — the only source of a
 * game's players available to {@code timeline-service}, needed by {@code ParadoxResolutionSagaImpl} to know who
 * a resolution phase is waiting on (design.md Decision 3).
 */
public interface EraPlayersPort {

    void save(UUID gameId, int eraNumber, List<UUID> playerIds);

    /**
     * @return {@link Optional#empty()} when no roster has been persisted for this era yet — distinct from a
     *     persisted but empty one, which a resolution phase must treat as final rather than as still pending
     *     ({@code EraStartedKafkaConsumer} consumes {@code game.events} in its own consumer group, so nothing
     *     orders it against the group that opens resolution phases)
     */
    Optional<List<UUID>> find(UUID gameId, int eraNumber);
}
