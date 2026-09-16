package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainProtectionArmed} wire fact — an accepted TAPESTRY armed protection. */
public record ChainProtectionArmedEvent(UUID gameId, int eraNumber, UUID chainId, UUID playerId) {}
