package io.github.temporalrift.timeline.application.command;

import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.AmplifyStatePort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard.EffectKind;

/** Applies a {@code PUSH}/{@code SUPPRESS}/{@code SWING} card effect to its target {@code FutureEvent}. */
@Service
class ApplyProbabilityShiftCommandHandler implements ApplyProbabilityShiftUseCase {

    private final FutureEventRepository futureEvents;
    private final ProbabilityRulesPort rules;
    private final AmplifyStatePort amplifyState;
    private final RoundLastCardPort roundLastCard;
    private final EventLastShiftPort eventLastShift;

    ApplyProbabilityShiftCommandHandler(
            FutureEventRepository futureEvents,
            ProbabilityRulesPort rules,
            AmplifyStatePort amplifyState,
            RoundLastCardPort roundLastCard,
            EventLastShiftPort eventLastShift) {
        this.futureEvents = futureEvents;
        this.rules = rules;
        this.amplifyState = amplifyState;
        this.roundLastCard = roundLastCard;
        this.eventLastShift = eventLastShift;
    }

    @Override
    public void apply(UUID gameId, int eraNumber, int roundNumber, UUID targetEventId, ProbabilityShift shift) {
        var futureEvent = futureEvents.findById(targetEventId);
        int baseMagnitude =
                switch (shift) {
                    case ProbabilityShift.Push p -> rules.pushShift();
                    case ProbabilityShift.Suppress s -> rules.suppressShift();
                    case ProbabilityShift.Swing sw -> rules.swingShift();
                    case ProbabilityShift.Restore r ->
                        throw new IllegalArgumentException(
                                "Restore is an internal undo mechanism, not a card-play entry point");
                };

        boolean amplified = amplifyState.isPending(gameId, eraNumber, roundNumber);
        int magnitude = amplified ? 2 * baseMagnitude : baseMagnitude;
        if (amplified) {
            amplifyState.clear(gameId, eraNumber, roundNumber);
        }

        var preShiftSnapshot =
                futureEvent.outcomes().stream().collect(Collectors.toMap(Outcome::outcomeId, Outcome::probability));

        var event = futureEvent.applyShift(shift, magnitude, rules.probabilityFloor(), rules.probabilityCeiling());
        futureEvents.append(targetEventId, event);

        eventLastShift.record(
                gameId,
                eraNumber,
                roundNumber,
                targetEventId,
                new EventShift(
                        shiftTypeOf(shift),
                        sourceOutcomeIdOf(shift),
                        targetOutcomeIdOf(shift),
                        magnitude,
                        preShiftSnapshot));
        roundLastCard.record(gameId, eraNumber, roundNumber, new LastCard(EffectKind.EVENT_EFFECT, targetEventId));
    }

    private static ShiftType shiftTypeOf(ProbabilityShift shift) {
        return switch (shift) {
            case ProbabilityShift.Push p -> ShiftType.PUSH;
            case ProbabilityShift.Suppress s -> ShiftType.SUPPRESS;
            case ProbabilityShift.Swing sw -> ShiftType.SWING;
            case ProbabilityShift.Restore r -> throw new IllegalArgumentException("Restore has no shift type");
        };
    }

    private static UUID sourceOutcomeIdOf(ProbabilityShift shift) {
        return shift instanceof ProbabilityShift.Swing sw ? sw.sourceOutcomeId() : null;
    }

    private static UUID targetOutcomeIdOf(ProbabilityShift shift) {
        return switch (shift) {
            case ProbabilityShift.Push p -> p.targetOutcomeId();
            case ProbabilityShift.Suppress s -> s.targetOutcomeId();
            case ProbabilityShift.Swing sw -> sw.targetOutcomeId();
            case ProbabilityShift.Restore r -> throw new IllegalArgumentException("Restore has no target outcome");
        };
    }
}
