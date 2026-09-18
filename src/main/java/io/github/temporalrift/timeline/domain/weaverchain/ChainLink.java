package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/** One causal link in a Weaver chain — confirmed if in {@link WeaverChain#links()}, pending otherwise. */
public record ChainLink(UUID eventId, UUID outcomeId, int eraNumber) {}
