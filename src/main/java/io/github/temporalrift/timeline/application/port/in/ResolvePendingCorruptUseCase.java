package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port: resolve every {@code CORRUPT} buffered for a round once that round's {@code ActionRoundClosed}
 * is consumed (design.md Decision 4). */
public interface ResolvePendingCorruptUseCase {

    void resolve(UUID gameId, int eraNumber, int roundNumber);
}
