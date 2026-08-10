package io.github.temporalrift.timeline.application.command;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ApplyMomentumBonusUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

/**
 * Applies Momentum's one-time declaration-time bonus (activist-declaration-effects capability, GDD §2.2)
 * immediately on consumption — outside the per-round buffering/replay model {@code ReplayRoundActionsCommandHandler}
 * uses for {@code CardPlayed}/{@code SpecialActionPlayed} (design.md "MOMENTUM applies immediately on
 * consumption..."). Reuses the same {@code PUSH}-shaped {@code FutureEvent.applyShift} floor/ceiling/redistribution
 * and sealed-outcome handling every other direct transfer goes through.
 */
@Service
class ApplyMomentumBonusCommandHandler implements ApplyMomentumBonusUseCase {

    private final FutureEventRepository futureEvents;
    private final ProbabilityRulesPort rules;

    ApplyMomentumBonusCommandHandler(FutureEventRepository futureEvents, ProbabilityRulesPort rules) {
        this.futureEvents = futureEvents;
        this.rules = rules;
    }

    @Override
    public void apply(UUID targetEventId, UUID targetOutcomeId) {
        var futureEvent = futureEvents.findById(targetEventId);
        var result = futureEvent.applyShift(
                new ProbabilityShift.Push(targetOutcomeId),
                rules.momentumBonus(),
                rules.probabilityFloor(),
                rules.probabilityCeiling());
        futureEvents.append(targetEventId, result);
    }
}
