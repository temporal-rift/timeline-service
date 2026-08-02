package io.github.temporalrift.timeline.application.command;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

/** Applies a {@code PUSH}/{@code SUPPRESS}/{@code SWING} card effect to its target {@code FutureEvent}. */
@Service
class ApplyProbabilityShiftCommandHandler implements ApplyProbabilityShiftUseCase {

    private final FutureEventRepository futureEvents;
    private final ProbabilityRulesPort rules;

    ApplyProbabilityShiftCommandHandler(FutureEventRepository futureEvents, ProbabilityRulesPort rules) {
        this.futureEvents = futureEvents;
        this.rules = rules;
    }

    @Override
    public void apply(UUID targetEventId, ProbabilityShift shift) {
        var futureEvent = futureEvents.findById(targetEventId);
        int magnitude =
                switch (shift) {
                    case ProbabilityShift.Push p -> rules.pushShift();
                    case ProbabilityShift.Suppress s -> rules.suppressShift();
                    case ProbabilityShift.Swing sw -> rules.swingShift();
                };
        var event = futureEvent.applyShift(shift, magnitude, rules.probabilityFloor(), rules.probabilityCeiling());
        futureEvents.append(targetEventId, event);
    }
}
