package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

/** Driven port for whether AMPLIFY's next-shift doubling is armed for a round (design.md Decision 3). */
public interface AmplifyStatePort {

    boolean isPending(UUID gameId, int eraNumber, int roundNumber);

    void arm(UUID gameId, int eraNumber, int roundNumber);

    void clear(UUID gameId, int eraNumber, int roundNumber);
}
