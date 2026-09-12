package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact removing one annihilated link — the chain stays open for rebuilding. */
public record ChainLinkInvalidated(UUID chainId, UUID eventId, UUID outcomeId) implements ChainFact {}
