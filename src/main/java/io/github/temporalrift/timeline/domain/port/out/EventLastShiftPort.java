package io.github.temporalrift.timeline.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven port for "the last probability shift applied to this FutureEvent this round" — what REDIRECT
 * retargets and what NULLIFY undoes via exact snapshot restore, not inverse arithmetic (design.md Decision 1/2).
 */
public interface EventLastShiftPort {

    void save(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId, EventShift shift);

    Optional<EventShift> find(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId);

    /** Called after an undo (NULLIFY) so a later REDIRECT in the same round finds no shift to retarget. */
    void clear(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId);

    /** {@code sourceOutcomeId} is populated for {@code SWING} only. {@code magnitude} is the actually-applied
     *  value (post-AMPLIFY-doubling if applicable), reused so a REDIRECT reapplies at the same force. */
    record EventShift(
            ShiftType shiftType,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            int magnitude,
            Map<UUID, Integer> preShiftSnapshot) {

        public enum ShiftType {
            PUSH,
            SUPPRESS,
            SWING
        }
    }
}
