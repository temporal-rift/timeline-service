package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

/** Publication record for the {@code ChainCompleted} wire fact. */
public record ChainCompletedEvent(UUID gameId, int eraNumber, UUID chainId, UUID playerId, List<ChainLinkEntry> links) {

    public ChainCompletedEvent {
        links = List.copyOf(links);
    }

    /** One completed link with the era its THREAD landed in. */
    public record ChainLinkEntry(UUID eventId, UUID outcomeId, int eraNumber) {}
}
