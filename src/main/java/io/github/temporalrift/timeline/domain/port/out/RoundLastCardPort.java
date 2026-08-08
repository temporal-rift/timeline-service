package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;
import java.util.UUID;

/** Driven port for "the most recently played card this round" — what NULLIFY targets (design.md Decision 1). */
public interface RoundLastCardPort {

    void save(UUID gameId, int eraNumber, int roundNumber, LastCard lastCard);

    Optional<LastCard> find(UUID gameId, int eraNumber, int roundNumber);

    /** {@code futureEventId} is populated for {@code EVENT_EFFECT}/{@code EVENT_STALLED}, null otherwise. */
    record LastCard(EffectKind effectKind, UUID futureEventId) {

        public enum EffectKind {
            AMPLIFY_ARMED,
            EVENT_EFFECT,
            EVENT_STALLED,
            NOOP
        }
    }
}
