package io.github.temporalrift.timeline.domain.futureevent;

import java.util.List;
import java.util.UUID;

/** A paradox {@link ParadoxDetector} found on one {@link FutureEvent}'s outcomes, before era-level assembly. */
public record DetectedParadox(ParadoxType type, List<UUID> affectedOutcomeIds, String description) {

    public DetectedParadox {
        affectedOutcomeIds = List.copyOf(affectedOutcomeIds);
    }
}
