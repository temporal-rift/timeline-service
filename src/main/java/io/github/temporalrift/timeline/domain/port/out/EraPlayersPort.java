package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for the player roster {@code EraStarted} carries — the only source of a
 * game's players available to {@code timeline-service}, needed by {@code ParadoxResolutionSagaImpl} to know who
 * a resolution phase is waiting on.
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

    /**
     * The highest era number an {@code EraStarted} has been recorded for — the only "current era" signal
     * available to timeline-service, which does not itself own era progression.
     *
     * @return {@link Optional#empty()} when no era has started for this game yet
     */
    Optional<Integer> findLatestEraNumber(UUID gameId);
}
