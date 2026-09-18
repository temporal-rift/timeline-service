package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact — the chain's pending link resolved differently and cleared without penalty. */
public record ChainLinkInvalidated(UUID chainId, UUID eventId, UUID outcomeId) implements ChainFact {}
