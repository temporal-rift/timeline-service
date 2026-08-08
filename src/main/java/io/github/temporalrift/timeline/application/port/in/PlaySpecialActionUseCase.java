package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port: play a {@code SEAL}, {@code ANNIHILATE}, or {@code CORRUPT} faction special (GDD §2.2). */
public interface PlaySpecialActionUseCase {

    void play(SpecialAction action);

    sealed interface SpecialAction {

        record Seal(UUID targetEventId, UUID targetOutcomeId) implements SpecialAction {}

        record Annihilate(UUID targetEventId, UUID targetOutcomeId) implements SpecialAction {}

        /** Buffered until {@code ActionRoundClosed} — the correlated card isn't knowable yet (design.md). */
        record Corrupt(UUID gameId, int eraNumber, int roundNumber, UUID targetPlayerId) implements SpecialAction {}

        /** Every other {@code specialAction} value (FORESIGHT, CASCADE, ...) — out of scope this slice. */
        record NoOp() implements SpecialAction {}
    }
}
