package io.github.temporalrift.timeline.application.command;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.PendingCorruptPort;

/**
 * Resolves {@code SEAL}/{@code ANNIHILATE} immediately, like the existing card-modifier effects (design.md
 * Decision 1) — each targets a single outcome directly in its own payload, with nothing to correlate.
 * {@code CORRUPT} only buffers itself here; its correlation and effect are resolved by
 * {@link ResolvePendingCorruptCommandHandler} on {@code ActionRoundClosed}.
 */
@Service
class PlaySpecialActionCommandHandler implements PlaySpecialActionUseCase {

    private final FutureEventRepository futureEvents;
    private final PendingCorruptPort pendingCorrupt;

    PlaySpecialActionCommandHandler(FutureEventRepository futureEvents, PendingCorruptPort pendingCorrupt) {
        this.futureEvents = futureEvents;
        this.pendingCorrupt = pendingCorrupt;
    }

    @Override
    public void play(SpecialAction action) {
        switch (action) {
            case SpecialAction.Seal s -> playSeal(s);
            case SpecialAction.Annihilate a -> playAnnihilate(a);
            case SpecialAction.Corrupt c ->
                pendingCorrupt.save(c.gameId(), c.eraNumber(), c.roundNumber(), c.targetPlayerId());
            case SpecialAction.NoOp n -> {
                // Unsupported specialAction this slice: eventId already claimed, no effect.
            }
        }
    }

    private void playSeal(SpecialAction.Seal s) {
        var futureEvent = futureEvents.findById(s.targetEventId());
        var sealed = futureEvent.sealOutcome(s.targetOutcomeId());
        futureEvents.append(s.targetEventId(), sealed);
    }

    private void playAnnihilate(SpecialAction.Annihilate a) {
        var futureEvent = futureEvents.findById(a.targetEventId());
        var annihilated = futureEvent.annihilateOutcome(a.targetOutcomeId());
        futureEvents.append(a.targetEventId(), annihilated);
    }
}
