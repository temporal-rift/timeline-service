package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port: play AMPLIFY/NULLIFY/REDIRECT/STALL or a no-op info/disruption card (GDD §3). */
public interface PlayCardModifierUseCase {

    void play(CardModifier modifier);

    sealed interface CardModifier {

        record Amplify(UUID gameId, int eraNumber, int roundNumber) implements CardModifier {}

        record Nullify(UUID gameId, int eraNumber, int roundNumber) implements CardModifier {}

        record Redirect(UUID gameId, int eraNumber, int roundNumber, UUID targetEventId, UUID targetOutcomeId)
                implements CardModifier {}

        record Stall(UUID gameId, int eraNumber, int roundNumber, UUID targetEventId) implements CardModifier {}

        /** INTERCEPT, SCAN, TRACE, DECOY, JAM — no probability or stalled-state effect. */
        record NoOp(UUID gameId, int eraNumber, int roundNumber) implements CardModifier {}
    }
}
