package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact — the chain's pending link confirmed. */
public record ChainLinkAdded(UUID chainId, UUID eventId, UUID outcomeId, int eraNumber) implements ChainFact {}
