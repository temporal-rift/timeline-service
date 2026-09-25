package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Internal, event-sourced fact — a {@code REWEAVE} moved the chain's pending link to a different outcome of the
 * same era. The link stays pending.
 */
public record ChainReAnchored(
        UUID chainId, UUID discardedEventId, UUID discardedOutcomeId, UUID eventId, UUID outcomeId, int eraNumber)
        implements ChainFact {}
