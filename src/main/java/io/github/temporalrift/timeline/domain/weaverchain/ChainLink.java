package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/**
 * One causal link between a past resolved outcome and its Weaver chain, anchored from a current-era
 * {@code (sourceEventId, sourceOutcomeId)} — the THREAD that established it, or the THREAD/REWEAVE whose
 * source a later REWEAVE inherited.
 */
public record ChainLink(UUID eventId, UUID outcomeId, int eraNumber, UUID sourceEventId, UUID sourceOutcomeId) {}
