package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact for one validated causal link — not published externally. */
public record ChainLinkAdded(UUID chainId, UUID eventId, UUID outcomeId, int eraNumber) implements ChainFact {}
