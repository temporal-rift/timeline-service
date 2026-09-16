package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Internal, event-sourced fact — not published externally in this shape. A {@code REWEAVE} discarded the
 * chain's newest link and replaced it with a different resolved past outcome, carrying forward the
 * discarded link's current-era anchor source so replay reproduces it without re-declaring one.
 */
public record ChainReAnchored(
        UUID chainId,
        UUID discardedEventId,
        UUID discardedOutcomeId,
        UUID eventId,
        UUID outcomeId,
        int eraNumber,
        UUID sourceEventId,
        UUID sourceOutcomeId)
        implements ChainFact {}
