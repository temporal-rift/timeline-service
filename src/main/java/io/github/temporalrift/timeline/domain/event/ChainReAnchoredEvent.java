package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainReAnchored} wire fact — an accepted REWEAVE re-anchored the chain. */
public record ChainReAnchoredEvent(
        UUID gameId,
        int eraNumber,
        UUID chainId,
        UUID playerId,
        UUID discardedEventId,
        UUID discardedOutcomeId,
        UUID linkedEventId,
        UUID linkedOutcomeId,
        int chainLength) {}
