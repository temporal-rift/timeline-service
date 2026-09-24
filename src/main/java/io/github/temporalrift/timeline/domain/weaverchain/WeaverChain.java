package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

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
        replayViolation(event).ifPresent(violation -> {
            throw new IllegalStateException(violation);
        });
        apply(event);
    }

    /** Why {@code event} is inconsistent with the current links; empty when it may be applied. */
    private Optional<String> replayViolation(ChainFact event) {
        return switch (event) {
            case ChainLinkThreaded e
            when pendingLink != null
                    || links.stream().anyMatch(link -> link.eventId().equals(e.eventId()))
                    || newestConfirmedLink().filter(notBefore(e.eraNumber())).isPresent() ->
                Optional.of("Invalid pending link for " + e.eventId());
            case ChainLinkAdded e
            when !pendingLinkMatches(e.eventId(), e.outcomeId()) ->
                Optional.of("No matching pending link to confirm for " + e.eventId());
            case ChainLinkInvalidated e
            when !pendingLinkMatches(e.eventId(), e.outcomeId()) ->
                Optional.of("No matching pending link to invalidate for " + e.eventId());
            case ChainReAnchored e
            when !newestLinkMatches(e.discardedEventId(), e.discardedOutcomeId()) ->
                Optional.of("Re-anchored link was never appended for " + e.discardedEventId());
            case ChainReAnchored e
            when linkBeforeNewest().filter(notBefore(e.eraNumber())).isPresent() ->
                Optional.of("Re-anchored link is not after its previous link for " + e.eventId());
            case ChainCompleted _
            when links.size() != COMPLETION_LENGTH ->
                Optional.of("ChainCompleted requires " + COMPLETION_LENGTH + " links");
            default -> Optional.empty();
        };
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

    private Optional<ChainLink> newestConfirmedLink() {
        return links.isEmpty() ? Optional.empty() : Optional.of(links.getLast());
    }

    /** The link a replacement of the newest link — pending or confirmed — must follow, if any. */
    private Optional<ChainLink> linkBeforeNewest() {
        if (pendingLink != null) {
            return newestConfirmedLink();
        }
        return links.size() < 2 ? Optional.empty() : Optional.of(links.get(links.size() - 2));
    }

    /** Matches a previous link that a link in {@code eraNumber} would not come after. */
    private static Predicate<ChainLink> notBefore(int eraNumber) {
        return previous -> previous.eraNumber() >= eraNumber;
    }

    private LinkEraNotSuccessiveException eraNotSuccessive(
            UUID eventId, UUID outcomeId, int eraNumber, ChainLink previous) {
        return new LinkEraNotSuccessiveException(chainId, eventId, outcomeId, eraNumber, previous.eraNumber());
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
        newestConfirmedLink().filter(notBefore(eraNumber)).ifPresent(previous -> {
            throw eraNotSuccessive(eventId, outcomeId, eraNumber, previous);
        });
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
        linkBeforeNewest().filter(notBefore(eraNumber)).ifPresent(previous -> {
            throw eraNotSuccessive(eventId, outcomeId, eraNumber, previous);
        });
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

    /** The chain's open pending link — a not-yet-resolved current-era THREAD prediction — if any. */
    public Optional<ChainLink> pendingLink() {
        return Optional.ofNullable(pendingLink);
    }
}
