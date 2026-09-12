package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Publication record for the {@code ThreadRejected} wire fact — private to the acting player. A null
 * {@code chainId} means the player had no active chain when the invalid THREAD arrived.
 */
public record ThreadRejectedEvent(
        UUID gameId,
        int eraNumber,
        UUID chainId,
        UUID playerId,
        UUID referencedEventId,
        UUID referencedOutcomeId,
        String reason) {}
