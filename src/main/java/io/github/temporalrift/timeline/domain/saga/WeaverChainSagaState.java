package io.github.temporalrift.timeline.domain.saga;

import java.util.UUID;

/**
 * Durable orchestration state for one Weaver player's chain — the saga stays open across era boundaries
 * until it completes, breaks, or the game ends. Link truth lives in the {@code WeaverChain} aggregate
 * stream; this record holds only orchestration flags (TAPESTRY arming and per-era usage).
 */
public record WeaverChainSagaState(
        UUID chainId,
        UUID gameId,
        UUID playerId,
        WeaverChainSagaStatus status,
        boolean tapestryProtected,
        Integer tapestryUsedEra) {}
