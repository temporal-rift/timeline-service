package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * A {@code FutureEvent}'s outcome probabilities did not sum to 100 after a round's priority-ordered actions
 * applied (sagas.md Resolution Saga compensation table). {@code GameEndedAbnormally}/{@code EraFailed} belong
 * to game-service's {@code session-event} — this repo publishes this fact only, temporal-rift/game-service#113
 * consumes it to trigger those. Not event-sourced.
 */
public record ResolutionFailed(UUID gameId, int eraNumber, UUID affectedEventId, String reason) {}
