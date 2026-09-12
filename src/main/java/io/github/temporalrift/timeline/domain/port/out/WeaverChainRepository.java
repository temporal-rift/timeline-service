package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
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

    /**
     * Appends several domain events to {@code chainId}'s stream atomically, so a multi-fact step such as the third
     * link plus its completion never persists half-written.
     */
    void appendAll(UUID chainId, List<Object> domainEvents);
}
