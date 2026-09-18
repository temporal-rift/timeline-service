package io.github.temporalrift.timeline.application.port.in;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.weaverchain.ChainLink;
import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;

/** Reads one Weaver player's own chain state for the gated chains endpoint. */
public interface GetChainUseCase {

    /**
     * @throws io.github.temporalrift.timeline.domain.membership.NotGameParticipantException when the
     *     caller is not a participant of the game
     * @throws io.github.temporalrift.timeline.domain.membership.NotWeaverException when the caller is
     *     a participant but not the Weavers faction
     * @throws io.github.temporalrift.timeline.domain.weaverchain.ChainNotFoundException when the
     *     requesting Weaver has no chain yet
     */
    Result get(UUID gameId, UUID playerId);

    record Result(
            UUID chainId,
            ChainStatus status,
            int chainLength,
            List<ChainLink> links,
            ChainLink pendingLink,
            boolean protectionArmed) {}
}
