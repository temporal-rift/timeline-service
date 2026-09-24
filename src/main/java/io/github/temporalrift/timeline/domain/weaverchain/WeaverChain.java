package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainFact;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidated;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreaded;
import io.github.temporalrift.timeline.domain.event.ChainReAnchored;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;

/**
 * Event-sourced aggregate for one Weaver player's causal chain. Rebuilt by {@link #replay(UUID, List)} or
 * {@link #restore(WeaverChainSnapshot, List)}, never loaded from a current-state row. The newest link may be
 * {@code pending} — anchored to a not-yet-resolved current-era outcome (a live THREAD prediction) — before it
 * confirms into {@link #links()}; at most one pending link is open at a time. Links are connected by era
 * succession: each link's era is strictly later than the era of the link before it.
 */
public final class WeaverChain {

    static final int COMPLETION_LENGTH = 3;

    private static final String PARAM_EVENT_ID = "eventId";
    private static final String PARAM_OUTCOME_ID = "outcomeId";

    private final UUID chainId;
    private final UUID playerId;
    private final UUID gameId;
    private final List<ChainLink> links;
    private ChainLink pendingLink;
    private ChainStatus status;

    private WeaverChain(
            UUID chainId,
            UUID playerId,
            UUID gameId,
            List<ChainLink> links,
            ChainLink pendingLink,
            ChainStatus status) {
        this.chainId = chainId;
        this.playerId = playerId;
        this.gameId = gameId;
        this.links = links;
        this.pendingLink = pendingLink;
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
                chain = new WeaverChain(chainId, player, game, new ArrayList<>(), null, ChainStatus.ACTIVE);
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
                snapshot.pendingLink(),
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
        if (event instanceof ChainLinkThreaded threaded
                && (pendingLink != null
                        || links.stream().anyMatch(link -> link.eventId().equals(threaded.eventId()))
                        || !isAfter(threaded.eraNumber(), newestConfirmedLink()))) {
            throw new IllegalStateException("Invalid pending link for " + threaded.eventId());
        }
        if (event instanceof ChainLinkAdded added && !pendingLinkMatches(added.eventId(), added.outcomeId())) {
            throw new IllegalStateException("No matching pending link to confirm for " + added.eventId());
        }
        if (event instanceof ChainLinkInvalidated invalidated
                && !pendingLinkMatches(invalidated.eventId(), invalidated.outcomeId())) {
            throw new IllegalStateException("No matching pending link to invalidate for " + invalidated.eventId());
        }
        if (event instanceof ChainReAnchored reAnchored
                && !newestLinkMatches(reAnchored.discardedEventId(), reAnchored.discardedOutcomeId())) {
            throw new IllegalStateException("Re-anchored link was never appended for " + reAnchored.discardedEventId());
        }
        if (event instanceof ChainReAnchored reAnchored && !isAfter(reAnchored.eraNumber(), linkBeforeNewest())) {
            throw new IllegalStateException(
                    "Re-anchored link is not after its previous link for " + reAnchored.eventId());
        }
        if (event instanceof ChainCompleted && links.size() != COMPLETION_LENGTH) {
            throw new IllegalStateException("ChainCompleted requires " + COMPLETION_LENGTH + " links");
        }
        apply(event);
    }

    private boolean pendingLinkMatches(UUID eventId, UUID outcomeId) {
        return pendingLink != null
                && pendingLink.eventId().equals(eventId)
                && pendingLink.outcomeId().equals(outcomeId);
    }

    private boolean newestLinkMatches(UUID eventId, UUID outcomeId) {
        if (pendingLink != null) {
            return pendingLinkMatches(eventId, outcomeId);
        }
        if (links.isEmpty()) {
            return false;
        }
        var newest = links.getLast();
        return newest.eventId().equals(eventId) && newest.outcomeId().equals(outcomeId);
    }

    private ChainLink newestConfirmedLink() {
        return links.isEmpty() ? null : links.getLast();
    }

    /** The link a replacement of the newest link — pending or confirmed — must follow, or {@code null}. */
    private ChainLink linkBeforeNewest() {
        if (pendingLink != null) {
            return newestConfirmedLink();
        }
        return links.size() < 2 ? null : links.get(links.size() - 2);
    }

    private static boolean isAfter(int eraNumber, ChainLink previous) {
        return previous == null || eraNumber > previous.eraNumber();
    }

    private void requireAfter(UUID eventId, UUID outcomeId, int eraNumber, ChainLink previous) {
        if (!isAfter(eraNumber, previous)) {
            throw new LinkEraNotSuccessiveException(chainId, eventId, outcomeId, eraNumber, previous.eraNumber());
        }
    }

    private void apply(ChainFact event) {
        switch (event) {
            case ChainLinkThreaded e -> pendingLink = new ChainLink(e.eventId(), e.outcomeId(), e.eraNumber());
            case ChainLinkAdded _ -> {
                links.add(pendingLink);
                pendingLink = null;
                if (links.size() == COMPLETION_LENGTH) {
                    status = ChainStatus.COMPLETED;
                }
            }
            case ChainLinkInvalidated _ -> pendingLink = null;
            case ChainCompleted _ -> status = ChainStatus.COMPLETED;
            case ChainBroken _ -> {
                status = ChainStatus.BROKEN;
                pendingLink = null;
            }
            case ChainReAnchored e -> {
                if (pendingLink != null) {
                    pendingLink = null;
                } else {
                    links.removeLast();
                }
                links.add(new ChainLink(e.eventId(), e.outcomeId(), e.eraNumber()));
            }
        }
    }

    /**
     * Accepts one {@code THREAD} play, opening a pending link anchored to a not-yet-resolved current-era
     * outcome. Rejected when the chain already has an open pending link, the event is already linked, or the era
     * is not after the newest confirmed link's era.
     */
    public ChainLinkThreaded threadPendingLink(UUID eventId, UUID outcomeId, int eraNumber) {
        Objects.requireNonNull(eventId, PARAM_EVENT_ID);
        Objects.requireNonNull(outcomeId, PARAM_OUTCOME_ID);
        if (status == ChainStatus.COMPLETED) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (status == ChainStatus.BROKEN) {
            throw new WeaverChainBrokenException(chainId);
        }
        if (pendingLink != null) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "chain already has an open pending link");
        }
        if (links.stream().anyMatch(link -> link.eventId().equals(eventId))) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "event already linked");
        }
        requireAfter(eventId, outcomeId, eraNumber, newestConfirmedLink());
        var fact = new ChainLinkThreaded(chainId, eventId, outcomeId, eraNumber);
        pendingLink = new ChainLink(eventId, outcomeId, eraNumber);
        return fact;
    }

    /**
     * Confirms the chain's open pending link — it resolved as predicted, was Tapestry-protected against an
     * Annihilate, or its {@code CHAIN_CONFLICT} paradox resolved in the Weaver's favor. Returns the stream facts
     * to append in order — one {@link ChainLinkAdded}, plus a {@link ChainCompleted} when the third link lands.
     */
    public List<WeaverChainEvent> confirmPendingLink() {
        if (pendingLink == null) {
            throw new InvalidChainLinkException(chainId, null, null, "chain has no pending link to confirm");
        }
        var confirmed = pendingLink;
        var linkAdded = new ChainLinkAdded(chainId, confirmed.eventId(), confirmed.outcomeId(), confirmed.eraNumber());
        links.add(confirmed);
        pendingLink = null;
        if (links.size() == COMPLETION_LENGTH) {
            status = ChainStatus.COMPLETED;
            return List.of(linkAdded, new ChainCompleted(chainId));
        }
        return List.of(linkAdded);
    }

    /**
     * Clears the chain's open pending link — it resolved to a different, non-annihilated outcome than
     * predicted. No penalty: chain length and confirmed links are unchanged, and the chain stays open.
     */
    public ChainLinkInvalidated clearPendingLink() {
        if (pendingLink == null) {
            throw new InvalidChainLinkException(chainId, null, null, "chain has no pending link to clear");
        }
        var cleared = pendingLink;
        pendingLink = null;
        return new ChainLinkInvalidated(chainId, cleared.eventId(), cleared.outcomeId());
    }

    /**
     * Discards this chain's newest link — pending or confirmed — and replaces it with a different resolved
     * past outcome in one indivisible step ({@code REWEAVE}). Length and earlier links are unchanged; the target's
     * era must be after the era of the link preceding the replaced one.
     */
    public ChainReAnchored reAnchor(ResolvedOutcome target) {
        Objects.requireNonNull(target, "target");
        var eventId = target.eventId();
        var outcomeId = target.outcomeId();
        var eraNumber = target.eraNumber();
        if (pendingLink == null && links.isEmpty()) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "chain has no links to re-anchor");
        }
        if (status == ChainStatus.COMPLETED) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (status == ChainStatus.BROKEN) {
            throw new WeaverChainBrokenException(chainId);
        }
        if (links.stream().anyMatch(link -> link.eventId().equals(eventId))) {
            throw new InvalidChainLinkException(chainId, eventId, outcomeId, "event already linked");
        }
        requireAfter(eventId, outcomeId, eraNumber, linkBeforeNewest());
        var discarded = pendingLink != null ? pendingLink : links.getLast();
        var reAnchored =
                new ChainReAnchored(chainId, discarded.eventId(), discarded.outcomeId(), eventId, outcomeId, eraNumber);
        if (pendingLink != null) {
            pendingLink = null;
        } else {
            links.removeLast();
        }
        links.add(new ChainLink(eventId, outcomeId, eraNumber));
        return reAnchored;
    }

    /** Marks this chain broken, preserving its confirmed links and discarding any open pending link. */
    public ChainBroken breakChain(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (status == ChainStatus.COMPLETED) {
            throw new WeaverChainCompletedException(chainId);
        }
        if (status == ChainStatus.BROKEN) {
            throw new WeaverChainBrokenException(chainId);
        }
        status = ChainStatus.BROKEN;
        pendingLink = null;
        return new ChainBroken(chainId, reason);
    }

    /** Captures this chain's full value state for snapshot persistence. */
    public WeaverChainSnapshot snapshot() {
        return new WeaverChainSnapshot(chainId, playerId, gameId, List.copyOf(links), pendingLink, status);
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

    /** The chain's open pending link — a not-yet-resolved current-era THREAD prediction — or {@code null}. */
    public ChainLink pendingLink() {
        return pendingLink;
    }
}
