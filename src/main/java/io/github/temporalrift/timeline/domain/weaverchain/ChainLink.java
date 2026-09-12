package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/** One causal link between a past resolved outcome and its Weaver chain. */
public record ChainLink(UUID eventId, UUID outcomeId, int eraNumber) {}
