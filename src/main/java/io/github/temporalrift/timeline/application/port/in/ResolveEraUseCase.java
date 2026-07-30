package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port: resolve every {@code FutureEvent} drawn for a game's era. */
public interface ResolveEraUseCase {

    void resolve(UUID gameId, int eraNumber);
}
