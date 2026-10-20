package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

/** Which games' inbound events this deployment acts on. */
public interface GameScope {

    boolean admits(UUID gameId);
}
