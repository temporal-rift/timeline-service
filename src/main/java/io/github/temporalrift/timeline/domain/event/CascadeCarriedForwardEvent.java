package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Publication record for the {@code CascadeCarriedForward} wire fact — a CASCADE armed against an outcome
 * erased in the prior era re-applied that erasure to the same outcome at the start of this era.
 */
public record CascadeCarriedForwardEvent(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId) {}
