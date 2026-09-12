package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;

/**
 * Event-sourced aggregate for one Weaver player's causal chain. Rebuilt by {@link #replay(UUID, List)} or
 * {@link #restore(WeaverChainSnapshot, List)}, never loaded from a current-state row.
 */
public final class WeaverChain {

    static final int COMPLETION_LENGTH = 3;

    private final UUID chainId;
    private final UUID playerId;
    private final UUID gameId;
    private final List<ChainLink> links;
    private ChainStatus status;

    private WeaverChain(UUID chainId, UUID playerId, UUID gameId, List<ChainLink> links, ChainStatus status) {
        this.chainId = chainId;
        this.playerId = playerId;
        this.gameId = gameId;
        this.links = links;
        this.status = status;
    }

    /** Rebuilds this aggregate by replaying its domain-event stream in order. */
    public static WeaverChain replay(UUID chainId, List<Object> history) {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(history, "history");
        if (history.isEmpty()) {
            throw new WeaverChainNotFoundException(chainId);
        }
        WeaverChain chain = null;
        for (var event : history) {
            if (event instanceof WeaverChainStarted started) {
                if (chain != null) {
                    throw new IllegalStateException("WeaverChainStarted replayed after initialization for " + chainId);
                }
                if (!Objects.equals(started.chainId(), chainId)) {
                    throw new IllegalStateException(
                            "Event belongs to WeaverChain " + started.chainId() + ", not " + chainId);
                }
                chain = new WeaverChain(
                        chainId, started.playerId(), started.gameId(), new ArrayList<>(), ChainStatus.ACTIVE);
            } else {
                if (chain == null) {
                    throw new IllegalStateException(
                            "Event replayed outside the started and active state for " + chainId);
                }
                chain.applyReplayed(event, chainId);
            }
        }
        return chain;
    }

    /** Rebuilds this aggregate from a snapshot plus the tail events appended after it. */
    public static WeaverChain restore(WeaverChainSnapshot snapshot, List<Object> tail) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(tail, "tail");
        var chain = new WeaverChain(
                snapshot.chainId(),
                snapshot.playerId(),
                snapshot.gameId(),
                new ArrayList<>(snapshot.links()),
                snapshot.status());
        for (var event : tail) {
            if (event instanceof WeaverChainStarted) {
                throw new IllegalStateException(
                        "WeaverChainStarted cannot follow a snapshot for " + snapshot.chainId());
            }
            chain.applyReplayed(event, snapshot.chainId());
        }
        return chain;
    }

    /**
     * Applies one replayed event, deriving completion from the link count so a stream missing its terminal fact
     * still rebuilds as completed. A repeated {@link ChainCompleted} is an idempotent echo; anything else after a
     * terminal state fails loudly. Facts carrying another chain's id are rejected before any state mutation.
     */
    private void applyReplayed(Object event, UUID chainId) {
        var eventChainId = chainIdOf(event);
        if (!Objects.equals(eventChainId, chainId)) {
            throw new IllegalStateException("Event belongs to WeaverChain " + eventChainId + ", not " + chainId);
        }
        if (event instanceof ChainCompleted && status == ChainStatus.COMPLETED) {
            return;
        }
        if (status != ChainStatus.ACTIVE) {
            throw new IllegalStateException("Event replayed outside the started and active state for " + chainId);
        }
        apply(event);
    }

    private static UUID chainIdOf(Object event) {
        return switch (event) {
            case WeaverChainStarted e -> e.chainId();
            case ChainLinkAdded e -> e.chainId();
            case ChainCompleted e -> e.chainId();
            case ChainBroken e -> e.chainId();
            default -> throw new IllegalArgumentException("Unknown WeaverChain domain event: " + event.getClass());
        };
    }

    private void apply(Object event) {
        switch (event) {
            case ChainLinkAdded e -> {
                links.add(new ChainLink(e.eventId(), e.outcomeId(), e.eraNumber()));
                if (links.size() >= COMPLETION_LENGTH) {
                    status = ChainStatus.COMPLETED;
                }
            }
            case ChainCompleted ignored -> status = ChainStatus.COMPLETED;
            case ChainBroken ignored -> status = ChainStatus.BROKEN;
            default -> throw new IllegalArgumentException("Unknown WeaverChain domain event: " + event.getClass());
        }
    }

    /**
     * Appends one validated causal link. Returns the stream facts to append in order — one {@link ChainLinkAdded},
     * plus a {@link ChainCompleted} when the third link lands.
     */
    public List<Object> addLink(UUID eventId, UUID outcomeId, int eraNumber, Set<ResolvedOutcome> resolvedOutcomes) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(outcomeId, "outcomeId");
        Objects.requireNonNull(resolvedOutcomes, "resolvedOutcomes");
        if (status == ChainStatus.COMPLETED) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (status == ChainStatus.BROKEN) {
            throw new WeaverChainBrokenException(chainId);
        }
        if (links.size() >= COMPLETION_LENGTH) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (!resolvedOutcomes.contains(new ResolvedOutcome(eventId, outcomeId))) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "outcome did not resolve");
        }
        if (links.stream().anyMatch(link -> link.eventId().equals(eventId))) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "event already linked");
        }
        var linkAdded = new ChainLinkAdded(chainId, eventId, outcomeId, eraNumber);
        links.add(new ChainLink(eventId, outcomeId, eraNumber));
        if (links.size() == COMPLETION_LENGTH) {
            status = ChainStatus.COMPLETED;
            return List.of(linkAdded, new ChainCompleted(chainId));
        }
        return List.of(linkAdded);
    }

    /** Marks this chain broken, preserving its links. */
    public ChainBroken breakChain(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (status == ChainStatus.COMPLETED) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (status == ChainStatus.BROKEN) {
            throw new WeaverChainBrokenException(chainId);
        }
        status = ChainStatus.BROKEN;
        return new ChainBroken(chainId, reason);
    }

    /** Captures this chain's full value state for snapshot persistence. */
    public WeaverChainSnapshot snapshot() {
        return new WeaverChainSnapshot(chainId, playerId, gameId, List.copyOf(links), status);
    }

    public UUID chainId() {
        return chainId;
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID gameId() {
        return gameId;
    }

    public ChainStatus status() {
        return status;
    }

    public int length() {
        return links.size();
    }

    public List<ChainLink> links() {
        return List.copyOf(links);
    }
}
