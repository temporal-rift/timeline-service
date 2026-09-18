package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Internal, event-sourced fact — a {@code REWEAVE} discarded the chain's newest link (pending or confirmed)
 * and replaced it with a different resolved past outcome.
 */
public record ChainReAnchored(
        UUID chainId, UUID discardedEventId, UUID discardedOutcomeId, UUID eventId, UUID outcomeId, int eraNumber)
        implements ChainFact {}
