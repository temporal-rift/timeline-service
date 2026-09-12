package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

/** Driven port for loading/appending to a {@link WeaverChain}'s event-sourced stream. */
public interface WeaverChainRepository {

    /**
     * Loads via latest snapshot plus tail, falling back to full replay.
     *
     * @throws io.github.temporalrift.timeline.domain.weaverchain.WeaverChainNotFoundException if no stream exists
     */
    WeaverChain findById(UUID chainId);

    /** Appends one new domain event to {@code chainId}'s stream, snapshotting every 20 events. */
    void append(UUID chainId, Object domainEvent);
}
