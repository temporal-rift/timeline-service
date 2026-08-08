package io.github.temporalrift.timeline.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;

/**
 * Driven port for "the shifter card a given player played this round" — what {@code CORRUPT} correlates
 * against on {@code ActionRoundClosed} (design.md Decision 4). A player plays at most one card per round
 * (session module invariant), so this is a last-value pointer keyed by player rather than a list.
 */
public interface RoundCardByPlayerPort {

    void save(UUID gameId, int eraNumber, int roundNumber, UUID playerId, PlayerCard card);

    Optional<PlayerCard> find(UUID gameId, int eraNumber, int roundNumber, UUID playerId);

    /**
     * {@code sourceOutcomeId} is populated for {@code SWING} only. {@code magnitude} and
     * {@code preShiftSnapshot} are the as-applied values, reused to restore-then-reapply inverted at
     * {@code ActionRoundClosed} the same way {@code REDIRECT} already does (design.md Decision 4).
     */
    record PlayerCard(
            UUID futureEventId,
            ShiftType shiftType,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            int magnitude,
            Map<UUID, Integer> preShiftSnapshot) {}
}
