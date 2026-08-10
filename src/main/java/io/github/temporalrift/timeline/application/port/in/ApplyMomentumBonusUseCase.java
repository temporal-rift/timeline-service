package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/**
 * Driving port: apply a Momentum declaration's one-time declaration-time bonus to a {@code FutureEvent} outcome
 * (activist-declaration-effects capability, GDD §2.2).
 */
public interface ApplyMomentumBonusUseCase {

    void apply(UUID targetEventId, UUID targetOutcomeId);
}
