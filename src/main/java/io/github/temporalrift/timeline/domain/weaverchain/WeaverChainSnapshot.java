package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.List;
import java.util.UUID;

/** Serializable value state of a {@link WeaverChain}, persisted as snapshot data. */
public record WeaverChainSnapshot(UUID chainId, UUID playerId, UUID gameId, List<ChainLink> links, ChainStatus status) {

    public WeaverChainSnapshot {
        links = List.copyOf(links);
    }
}
