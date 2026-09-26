package io.github.temporalrift.timeline.domain.futureevent;

import java.util.Objects;

/** One same-round effect resolved by {@link FutureEvent#applySimultaneousShifts}, at its final magnitude. */
public record SimultaneousShift(ProbabilityShift shift, int magnitude) {

    public SimultaneousShift {
        Objects.requireNonNull(shift, "shift");
    }
}
