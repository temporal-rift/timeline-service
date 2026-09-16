package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Publication record for the {@code ChainProtectionConsumed} wire fact — an armed TAPESTRY protection
 * absorbed an erasure that would otherwise have invalidated the identified link.
 */
public record ChainProtectionConsumedEvent(
        UUID gameId, int eraNumber, UUID chainId, UUID playerId, UUID protectedEventId, UUID protectedOutcomeId) {}
