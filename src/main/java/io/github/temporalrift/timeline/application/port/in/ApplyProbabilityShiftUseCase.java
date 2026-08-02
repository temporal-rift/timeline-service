package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;

/** Driving port: apply a probability-shifter card's effect to one {@code FutureEvent}. */
public interface ApplyProbabilityShiftUseCase {

    void apply(UUID targetEventId, ProbabilityShift shift);
}
