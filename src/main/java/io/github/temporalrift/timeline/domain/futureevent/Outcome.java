package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** One of a {@link FutureEvent}'s possible results. */
public record Outcome(UUID outcomeId, String description, int probability, boolean sealed, boolean annihilated) {

    /** Convenience constructor for the common case of an outcome that is neither sealed nor annihilated. */
    public Outcome(UUID outcomeId, String description, int probability) {
        this(outcomeId, description, probability, false, false);
    }
}
