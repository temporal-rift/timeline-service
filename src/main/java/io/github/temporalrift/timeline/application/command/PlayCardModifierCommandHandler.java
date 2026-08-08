package io.github.temporalrift.timeline.application.command;

import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
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
        roundLastCard.save(a.gameId(), a.eraNumber(), a.roundNumber(), new LastCard(EffectKind.AMPLIFY_ARMED, null));
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
            case NOOP -> {
                // Nothing to undo — the most recent card had no effect.
            }
        }
        roundLastCard.save(n.gameId(), n.eraNumber(), n.roundNumber(), new LastCard(EffectKind.NOOP, null));
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
        var shiftOpt = eventLastShift.find(r.gameId(), r.eraNumber(), r.roundNumber(), r.targetEventId());
        if (shiftOpt.isEmpty()) {
            recordIneffectiveRedirect(r);
            return;
        }
        var shift = shiftOpt.get();
        var futureEvent = futureEvents.findById(r.targetEventId());
        if (!isValidRedirectTarget(futureEvent, shift, r.targetOutcomeId())) {
            recordIneffectiveRedirect(r);
            return;
        }

        // The snapshot a later NULLIFY must restore to is the state right before THIS card ran, not the
        // state before the original shift being redirected — otherwise NULLIFY-after-REDIRECT would also
        // undo the original shift.
        var preRedirectSnapshot =
                futureEvent.outcomes().stream().collect(Collectors.toMap(Outcome::outcomeId, Outcome::probability));

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

        eventLastShift.save(
                r.gameId(),
                r.eraNumber(),
                r.roundNumber(),
                r.targetEventId(),
                new EventLastShiftPort.EventShift(
                        shift.shiftType(),
                        shift.sourceOutcomeId(),
                        r.targetOutcomeId(),
                        magnitude,
                        preRedirectSnapshot));
        roundLastCard.save(
                r.gameId(), r.eraNumber(), r.roundNumber(), new LastCard(EffectKind.EVENT_EFFECT, r.targetEventId()));
    }

    /** Unknown outcome, or a SWING redirected to its own recorded source, would otherwise throw downstream. */
    private static boolean isValidRedirectTarget(
            FutureEvent futureEvent, EventLastShiftPort.EventShift shift, UUID targetOutcomeId) {
        boolean knownOutcome =
                futureEvent.outcomes().stream().anyMatch(o -> o.outcomeId().equals(targetOutcomeId));
        boolean swingToOwnSource =
                shift.shiftType() == ShiftType.SWING && targetOutcomeId.equals(shift.sourceOutcomeId());
        return knownOutcome && !swingToOwnSource;
    }

    private void recordIneffectiveRedirect(CardModifier.Redirect r) {
        roundLastCard.save(r.gameId(), r.eraNumber(), r.roundNumber(), new LastCard(EffectKind.NOOP, null));
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
        if (futureEvent.stalled()) {
            // Already stalled by an earlier STALL this era that hasn't resolved yet — this card changes
            // nothing, so a NULLIFY targeting it must not un-stall the event (that would also cancel the
            // earlier STALL still doing the work, not just "the most recent card").
            roundLastCard.save(s.gameId(), s.eraNumber(), s.roundNumber(), new LastCard(EffectKind.NOOP, null));
            return;
        }
        var stalled = futureEvent.markStalled();
        futureEvents.append(s.targetEventId(), stalled);
        roundLastCard.save(
                s.gameId(), s.eraNumber(), s.roundNumber(), new LastCard(EffectKind.EVENT_STALLED, s.targetEventId()));
    }

    private void playNoOp(CardModifier.NoOp n) {
        roundLastCard.save(n.gameId(), n.eraNumber(), n.roundNumber(), new LastCard(EffectKind.NOOP, null));
    }
}
