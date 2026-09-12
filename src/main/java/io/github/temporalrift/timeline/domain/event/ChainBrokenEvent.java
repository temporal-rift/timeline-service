package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Publication record for the {@code ChainBroken} wire fact. */
public record ChainBrokenEvent(
        UUID gameId, int eraNumber, UUID chainId, UUID brokenByPlayerId, UUID targetPlayerId, int chainLengthAtBreak) {}
