package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainLinkThreaded} wire fact — THREAD created a pending link. */
public record ChainLinkThreadedEvent(
        UUID gameId, int eraNumber, UUID chainId, UUID playerId, UUID eventId, UUID outcomeId) {}
