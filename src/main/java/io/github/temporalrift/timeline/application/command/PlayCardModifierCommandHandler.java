package io.github.temporalrift.timeline.application.command;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.AmplifyStatePort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard.EffectKind;

/**
 * Resolves AMPLIFY/NULLIFY/REDIRECT/STALL and the info/disruption no-op cards (GDD §3 "Group 2 —
 * Information", "Group 3 — Disruption"). NULLIFY and REDIRECT both undo via an exact pre-shift snapshot
 * restore rather than inverse arithmetic (design.md Decision 2), since {@code FutureEvent}'s floor/ceiling
 * clamping is not symmetric.
 */
@Service
class PlayCardModifierCommandHandler implements PlayCardModifierUseCase {

    private final FutureEventRepository futureEvents;
    private final ProbabilityRulesPort rules;
    private final AmplifyStatePort amplifyState;
    private final RoundLastCardPort roundLastCard;
    private final EventLastShiftPort eventLastShift;

    PlayCardModifierCommandHandler(
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
    public void play(CardModifier modifier) {
        switch (modifier) {
            case CardModifier.Amplify a -> playAmplify(a);
            case CardModifier.Nullify n -> playNullify(n);
            case CardModifier.Redirect r -> playRedirect(r);
            case CardModifier.Stall s -> playStall(s);
            case CardModifier.NoOp n -> playNoOp(n);
        }
    }

    private void playAmplify(CardModifier.Amplify a) {
        amplifyState.arm(a.gameId(), a.eraNumber(), a.roundNumber());
        roundLastCard.record(a.gameId(), a.eraNumber(), a.roundNumber(), new LastCard(EffectKind.AMPLIFY_ARMED, null));
    }

    private void playNullify(CardModifier.Nullify n) {
        var last = roundLastCard.find(n.gameId(), n.eraNumber(), n.roundNumber());
        if (last.isEmpty()) {
            return;
        }
        switch (last.get().effectKind()) {
            case AMPLIFY_ARMED -> amplifyState.clear(n.gameId(), n.eraNumber(), n.roundNumber());
            case EVENT_EFFECT ->
                undoEventEffect(
                        n.gameId(), n.eraNumber(), n.roundNumber(), last.get().futureEventId());
            case EVENT_STALLED -> unstall(last.get().futureEventId());
            case NOOP -> {}
        }
        roundLastCard.record(n.gameId(), n.eraNumber(), n.roundNumber(), new LastCard(EffectKind.NOOP, null));
    }

    private void undoEventEffect(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId) {
        eventLastShift.find(gameId, eraNumber, roundNumber, futureEventId).ifPresent(shift -> {
            var futureEvent = futureEvents.findById(futureEventId);
            var restored = futureEvent.applyShift(
                    new ProbabilityShift.Restore(shift.preShiftSnapshot()),
                    0,
                    rules.probabilityFloor(),
                    rules.probabilityCeiling());
            futureEvents.append(futureEventId, restored);
            eventLastShift.clear(gameId, eraNumber, roundNumber, futureEventId);
        });
    }

    private void unstall(UUID futureEventId) {
        var futureEvent = futureEvents.findById(futureEventId);
        futureEvents.append(futureEventId, futureEvent.clearStalled());
    }

    private void playRedirect(CardModifier.Redirect r) {
        eventLastShift
                .find(r.gameId(), r.eraNumber(), r.roundNumber(), r.targetEventId())
                .ifPresent(shift -> {
                    var futureEvent = futureEvents.findById(r.targetEventId());
                    var restored = futureEvent.applyShift(
                            new ProbabilityShift.Restore(shift.preShiftSnapshot()),
                            0,
                            rules.probabilityFloor(),
                            rules.probabilityCeiling());
                    futureEvents.append(r.targetEventId(), restored);

                    boolean amplified = amplifyState.isPending(r.gameId(), r.eraNumber(), r.roundNumber());
                    int magnitude = amplified ? 2 * shift.magnitude() : shift.magnitude();
                    if (amplified) {
                        amplifyState.clear(r.gameId(), r.eraNumber(), r.roundNumber());
                    }

                    var reapplied = futureEvent.applyShift(
                            toShift(shift.shiftType(), shift.sourceOutcomeId(), r.targetOutcomeId()),
                            magnitude,
                            rules.probabilityFloor(),
                            rules.probabilityCeiling());
                    futureEvents.append(r.targetEventId(), reapplied);

                    eventLastShift.record(
                            r.gameId(),
                            r.eraNumber(),
                            r.roundNumber(),
                            r.targetEventId(),
                            new EventLastShiftPort.EventShift(
                                    shift.shiftType(),
                                    shift.sourceOutcomeId(),
                                    r.targetOutcomeId(),
                                    magnitude,
                                    shift.preShiftSnapshot()));
                    roundLastCard.record(
                            r.gameId(),
                            r.eraNumber(),
                            r.roundNumber(),
                            new LastCard(EffectKind.EVENT_EFFECT, r.targetEventId()));
                });
    }

    private static ProbabilityShift toShift(ShiftType type, UUID sourceOutcomeId, UUID targetOutcomeId) {
        return switch (type) {
            case PUSH -> new ProbabilityShift.Push(targetOutcomeId);
            case SUPPRESS -> new ProbabilityShift.Suppress(targetOutcomeId);
            case SWING -> new ProbabilityShift.Swing(sourceOutcomeId, targetOutcomeId);
        };
    }

    private void playStall(CardModifier.Stall s) {
        var futureEvent = futureEvents.findById(s.targetEventId());
        var stalled = futureEvent.markStalled();
        futureEvents.append(s.targetEventId(), stalled);
        roundLastCard.record(
                s.gameId(), s.eraNumber(), s.roundNumber(), new LastCard(EffectKind.EVENT_STALLED, s.targetEventId()));
    }

    private void playNoOp(CardModifier.NoOp n) {
        roundLastCard.record(n.gameId(), n.eraNumber(), n.roundNumber(), new LastCard(EffectKind.NOOP, null));
    }
}
