package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainLinkInvalidated} wire fact. */
public record ChainLinkInvalidatedEvent(
        UUID gameId,
        int eraNumber,
        UUID chainId,
        UUID playerId,
        UUID invalidatedEventId,
        UUID invalidatedOutcomeId,
        int chainLength) {}
