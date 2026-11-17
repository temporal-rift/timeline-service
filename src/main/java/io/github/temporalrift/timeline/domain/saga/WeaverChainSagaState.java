package io.github.temporalrift.timeline.domain.saga;

import java.util.UUID;

/**
 * Durable orchestration state for one Weaver player's chain — the saga stays open across era boundaries
 * until it completes, breaks, or the game ends. Link truth lives in the {@code WeaverChain} aggregate
 * stream; this record holds only orchestration flags (TAPESTRY arming and per-era usage for both TAPESTRY
 * and REWEAVE, and the era whose consumed protection keeps the chain from breaking).
 *
 * @param breakGuardEra the era in which Tapestry protection was consumed against the pending link while the
 *     paradox stays raised: a {@code CHAIN_CONFLICT} of that era that cascades clears the link instead of breaking
 *     the chain. {@code null} when no protection was consumed that way.
 */
public record WeaverChainSagaState(
        UUID chainId,
        UUID gameId,
        UUID playerId,
        WeaverChainSagaStatus status,
        boolean tapestryProtected,
        Integer tapestryUsedEra,
        Integer reweaveUsedEra,
        Integer breakGuardEra) {}
