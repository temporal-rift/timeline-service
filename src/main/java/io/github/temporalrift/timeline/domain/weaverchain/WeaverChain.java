package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainFact;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
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
    public static WeaverChain replay(UUID chainId, List<? extends WeaverChainEvent> history) {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(history, "history");
        if (history.isEmpty()) {
            throw new WeaverChainNotFoundException(chainId);
        }
        WeaverChain chain = null;
        // if-chains, not a switch: every guard below depends on the stream content, which keeps this path quiet.
        for (var event : history) {
            if (event instanceof WeaverChainStarted(var id, var player, var game)) {
                if (chain != null) {
                    throw new IllegalStateException("WeaverChainStarted replayed after initialization for " + chainId);
                }
                if (!Objects.equals(id, chainId)) {
                    throw new IllegalStateException("Event belongs to WeaverChain " + id + ", not " + chainId);
                }
                chain = new WeaverChain(chainId, player, game, new ArrayList<>(), ChainStatus.ACTIVE);
            } else if (event instanceof ChainFact fact) {
                if (chain == null) {
                    throw new IllegalStateException(
                            "Event replayed outside the started and active state for " + chainId);
                }
                chain.applyReplayed(fact, chainId);
            }
        }
        return chain;
    }

    /** Rebuilds this aggregate from a snapshot plus the tail events appended after it. */
    public static WeaverChain restore(WeaverChainSnapshot snapshot, List<? extends WeaverChainEvent> tail) {
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
            if (event instanceof ChainFact fact) {
                chain.applyReplayed(fact, snapshot.chainId());
            }
        }
        return chain;
    }

    /**
     * Applies one replayed event, deriving completion from the link count so a stream missing its terminal fact
     * still rebuilds as completed. A repeated {@link ChainCompleted} is an idempotent echo; anything else after a
     * terminal state fails loudly. Facts carrying another chain's id, repeating a linked event, or completing a
     * short chain are rejected before any state mutation.
     */
    private void applyReplayed(ChainFact event, UUID chainId) {
        if (!Objects.equals(event.chainId(), chainId)) {
            throw new IllegalStateException("Event belongs to WeaverChain " + event.chainId() + ", not " + chainId);
        }
        if (event instanceof ChainCompleted && status == ChainStatus.COMPLETED) {
            return;
        }
        if (status != ChainStatus.ACTIVE) {
            throw new IllegalStateException("Event replayed outside the started and active state for " + chainId);
        }
        if (event instanceof ChainLinkAdded added
                && links.stream().anyMatch(link -> link.eventId().equals(added.eventId()))) {
            throw new IllegalStateException("Duplicate event link for " + added.eventId());
        }
        if (event instanceof ChainCompleted && links.size() != COMPLETION_LENGTH) {
            throw new IllegalStateException("ChainCompleted requires " + COMPLETION_LENGTH + " links");
        }
        apply(event);
    }

    private void apply(ChainFact event) {
        switch (event) {
            case ChainLinkAdded e -> {
                links.add(new ChainLink(e.eventId(), e.outcomeId(), e.eraNumber()));
                if (links.size() >= COMPLETION_LENGTH) {
                    status = ChainStatus.COMPLETED;
                }
            }
            case ChainCompleted _ -> status = ChainStatus.COMPLETED;
            case ChainBroken _ -> status = ChainStatus.BROKEN;
        }
    }

    /**
     * Appends one validated causal link. Returns the stream facts to append in order — one {@link ChainLinkAdded},
     * plus a {@link ChainCompleted} when the third link lands.
     */
    public List<WeaverChainEvent> addLink(
            UUID eventId, UUID outcomeId, int eraNumber, Set<ResolvedOutcome> resolvedOutcomes) {
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
