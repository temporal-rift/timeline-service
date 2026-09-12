package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact opening a chain stream — not published externally. */
public record WeaverChainStarted(UUID chainId, UUID playerId, UUID gameId) implements WeaverChainEvent {}
