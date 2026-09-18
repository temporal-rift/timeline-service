package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainLinkAdded} wire fact — distinct from the stream fact. */
public record ChainLinkAddedEvent(
        UUID gameId,
        UUID chainId,
        UUID playerId,
        UUID linkedEventId,
        UUID linkedOutcomeId,
        int chainLength,
        UUID previousLinkEventId) {}
