package io.github.temporalrift.timeline.application.command;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ResolvePendingCorruptUseCase;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.PendingCorruptPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundCardByPlayerPort;
import io.github.temporalrift.timeline.domain.port.out.RoundCardByPlayerPort.PlayerCard;

/**
 * On {@code ActionRoundClosed}, correlates each buffered {@code CORRUPT} to the target player's
 * {@code PUSH}/{@code SUPPRESS}/{@code SWING} in that round and applies its inverted effect instead of the
 * originally submitted one — restore the pre-shift snapshot, then reapply reversed, the same primitive
 * {@code REDIRECT} already uses (design.md Decision 4). A {@code CORRUPT} with no matching card, whose
 * correlated card's outcome is already sealed, or whose correlated card is no longer that
 * {@code FutureEvent}'s current last shift this round (another player's card landed on the same event
 * afterward — restoring a stale snapshot would silently discard it), has no effect (SEAL outranks CORRUPT —
 * sagas.md).
 */
@Service
class ResolvePendingCorruptCommandHandler implements ResolvePendingCorruptUseCase {

    private final PendingCorruptPort pendingCorrupt;
    private final RoundCardByPlayerPort roundCardByPlayer;
    private final EventLastShiftPort eventLastShift;
    private final FutureEventRepository futureEvents;
    private final ProbabilityRulesPort rules;

    ResolvePendingCorruptCommandHandler(
            PendingCorruptPort pendingCorrupt,
            RoundCardByPlayerPort roundCardByPlayer,
            EventLastShiftPort eventLastShift,
            FutureEventRepository futureEvents,
            ProbabilityRulesPort rules) {
        this.pendingCorrupt = pendingCorrupt;
        this.roundCardByPlayer = roundCardByPlayer;
        this.eventLastShift = eventLastShift;
        this.futureEvents = futureEvents;
        this.rules = rules;
    }

    @Override
    public void resolve(UUID gameId, int eraNumber, int roundNumber) {
        for (var targetPlayerId : pendingCorrupt.find(gameId, eraNumber, roundNumber)) {
            roundCardByPlayer
                    .find(gameId, eraNumber, roundNumber, targetPlayerId)
                    .ifPresent(card -> invert(gameId, eraNumber, roundNumber, card));
        }
    }

    private void invert(UUID gameId, int eraNumber, int roundNumber, PlayerCard card) {
        if (!isStillLastShiftOnEvent(gameId, eraNumber, roundNumber, card)) {
            // Another player's card landed on the same FutureEvent after this one — this card's
            // preShiftSnapshot predates that later shift, so restoring it would silently discard the
            // later player's effect (not just this one's). Leave the event as-is instead.
            return;
        }
        var futureEvent = futureEvents.findById(card.futureEventId());
        if (isSealed(futureEvent, card)) {
            // SEAL outranks CORRUPT: neither the original nor the inverted effect applies, and this
            // suppression is not itself a breach — the card was never re-applied against the seal.
            return;
        }
        int floor = rules.probabilityFloor();
        int ceiling = rules.probabilityCeiling();

        var restored = futureEvent.applyShift(new ProbabilityShift.Restore(card.preShiftSnapshot()), 0, floor, ceiling);
        futureEvents.append(card.futureEventId(), restored);

        var inverted = futureEvent.applyShift(invertedShift(card), invertedMagnitude(card), floor, ceiling);
        futureEvents.append(card.futureEventId(), inverted);
    }

    private boolean isStillLastShiftOnEvent(UUID gameId, int eraNumber, int roundNumber, PlayerCard card) {
        var expected = new EventShift(
                card.shiftType(),
                card.sourceOutcomeId(),
                card.targetOutcomeId(),
                card.magnitude(),
                card.preShiftSnapshot());
        return eventLastShift
                .find(gameId, eraNumber, roundNumber, card.futureEventId())
                .map(expected::equals)
                .orElse(false);
    }

    /**
     * PUSH/SUPPRESS magnitudes are signed (positive/negative respectively — {@code ProbabilityRulesPort}
     * convention); inverting one into the other negates it. SWING's magnitude is an unsigned distance, so
     * reversing its direction via {@link #invertedShift} needs no magnitude change.
     */
    private static int invertedMagnitude(PlayerCard card) {
        return card.shiftType() == ShiftType.SWING ? card.magnitude() : -card.magnitude();
    }

    private static boolean isSealed(FutureEvent futureEvent, PlayerCard card) {
        return futureEvent.outcomes().stream()
                .anyMatch(o -> o.sealed()
                        && (o.outcomeId().equals(card.targetOutcomeId())
                                || o.outcomeId().equals(card.sourceOutcomeId())));
    }

    private static ProbabilityShift invertedShift(PlayerCard card) {
        return switch (card.shiftType()) {
            case PUSH -> new ProbabilityShift.Suppress(card.targetOutcomeId());
            case SUPPRESS -> new ProbabilityShift.Push(card.targetOutcomeId());
            case SWING -> new ProbabilityShift.Swing(card.targetOutcomeId(), card.sourceOutcomeId());
        };
    }
}
