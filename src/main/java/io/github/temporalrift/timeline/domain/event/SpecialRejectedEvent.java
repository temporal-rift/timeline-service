package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Publication record for the {@code SpecialRejected} wire fact — private rejection of a TAPESTRY, REWEAVE, or
 * CASCADE play whose prerequisites were not met. {@code specialAction} carries the wire enum name.
 */
public record SpecialRejectedEvent(
        UUID gameId,
        int eraNumber,
        UUID playerId,
        String specialAction,
        UUID chainId,
        UUID targetEventId,
        UUID targetOutcomeId,
        String reason) {}
