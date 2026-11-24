package io.github.temporalrift.timeline.domain.futureevent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

/**
 * Detects paradox conditions on a {@link FutureEvent}'s final, post-effects outcome state. Plain
 * domain logic — no framework dependency — so it can run ahead of the weighted draw in the resolution use case.
 * A tie or an annihilated leader is not a paradox by itself: the draw settles both. A single call reports every
 * paradox type independently satisfied by the given state, not just the first one found.
 */
public final class ParadoxDetector {

    private ParadoxDetector() {}

    /**
     * {@code collidedPairs} is {@link FutureEvent#collidedPairs()}. Detection stays a pure function of
     * the final outcome state and this era's collision history.
     */
    public static List<DetectedParadox> detect(List<Outcome> outcomes, List<CollidedPair> collidedPairs) {
        return detect(outcomes, collidedPairs, null, List.of());
    }

    /**
     * Chain-aware detection for one active event: {@code eventId} is the event under evaluation and
     * {@code chains} are the game's currently loaded chains. Only {@code ACTIVE} chains linking
     * {@code eventId} participate; a null event id or empty chain input reports no conflict.
     */
    public static List<DetectedParadox> detect(
            List<Outcome> outcomes, List<CollidedPair> collidedPairs, UUID eventId, List<WeaverChain> chains) {
        return detect(outcomes, collidedPairs, eventId, chains, null);
    }

    /**
     * Detection for one active event from its own era state: its outcomes, its collided pairs and, while
     * {@code fateContradiction} is on, its written outcome.
     */
    public static List<DetectedParadox> detect(FutureEvent event, List<WeaverChain> chains, boolean fateContradiction) {
        UUID written = fateContradiction
                ? event.writtenOutcome().map(WrittenOutcome::outcomeId).orElse(null)
                : null;
        return detect(event.outcomes(), event.collidedPairs(), event.id(), chains, written);
    }

    /**
     * Full detection for one active event. {@code contestedWrittenOutcomeId} is the event's written outcome when the
     * fate-contradiction rule is on, otherwise {@code null}; an annihilated written outcome is a
     * {@code FATE_CONTRADICTION}.
     */
    public static List<DetectedParadox> detect(
            List<Outcome> outcomes,
            List<CollidedPair> collidedPairs,
            UUID eventId,
            List<WeaverChain> chains,
            UUID contestedWrittenOutcomeId) {
        var paradoxes = new ArrayList<DetectedParadox>();
        paradoxes.addAll(detectDeadHeat(outcomes, collidedPairs));
        paradoxes.addAll(detectImpossibleErasure(outcomes));
        paradoxes.addAll(detectChainConflict(eventId, outcomes, chains));
        paradoxes.addAll(detectFateContradiction(outcomes, contestedWrittenOutcomeId));
        return List.copyOf(paradoxes);
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code FATE_CONTRADICTION} when the event's written outcome is
     * annihilated — the timeline erased what a Prophet wrote. A writing applies before its round's Annihilates and an
     * annihilated outcome cannot be written, so such an erasure always came in the write round or later.
     */
    private static List<DetectedParadox> detectFateContradiction(List<Outcome> outcomes, UUID writtenOutcomeId) {
        if (writtenOutcomeId == null) {
            return List.of();
        }
        boolean erased = outcomes.stream()
                .anyMatch(
                        outcome -> outcome.annihilated() && outcome.outcomeId().equals(writtenOutcomeId));
        if (!erased) {
            return List.of();
        }
        return List.of(new DetectedParadox(
                ParadoxType.FATE_CONTRADICTION,
                List.of(writtenOutcomeId),
                "Written outcome " + writtenOutcomeId + " was annihilated"));
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code DEAD_HEAT} when two or more non-annihilated outcomes share
     * the highest, positive non-annihilated probability and a {@code COLLIDE} this era equalized a pair among them —
     * {@code affectedOutcomeIds} contains every tied outcome's id. Ties reached any other way go to the draw.
     */
    private static List<DetectedParadox> detectDeadHeat(List<Outcome> outcomes, List<CollidedPair> collidedPairs) {
        var nonAnnihilated = outcomes.stream().filter(o -> !o.annihilated()).toList();
        if (nonAnnihilated.isEmpty()) {
            return List.of();
        }
        int highest =
                nonAnnihilated.stream().mapToInt(Outcome::probability).max().orElseThrow();
        var tied = nonAnnihilated.stream()
                .filter(o -> o.probability() == highest)
                .map(Outcome::outcomeId)
                .toList();
        if (highest == 0 || tied.size() < 2 || collidedPairs.stream().noneMatch(pair -> pair.within(tied))) {
            return List.of();
        }
        return List.of(new DetectedParadox(
                ParadoxType.DEAD_HEAT, tied, "Collided outcomes " + tied + " are tied at the highest eligible weight"));
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code IMPOSSIBLE_ERASURE} when erasure leaves the
     * non-annihilated outcomes no weight to draw from (including none remaining) — {@code affectedOutcomeIds}
     * contains every annihilated outcome's id.
     */
    private static List<DetectedParadox> detectImpossibleErasure(List<Outcome> outcomes) {
        var annihilated = outcomes.stream()
                .filter(Outcome::annihilated)
                .map(Outcome::outcomeId)
                .toList();
        int eligibleWeight = outcomes.stream()
                .filter(o -> !o.annihilated())
                .mapToInt(Outcome::probability)
                .sum();
        if (annihilated.isEmpty() || eligibleWeight > 0) {
            return List.of();
        }
        return List.of(new DetectedParadox(
                ParadoxType.IMPOSSIBLE_ERASURE,
                annihilated,
                "Annihilated outcomes " + annihilated + " leave no eligible outcome with weight to draw"));
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code CHAIN_CONFLICT} per active chain whose pending link
     * names {@code eventId} and whose named outcome was annihilated — the timeline erased the outcome the
     * chain's causal claim depends on. A Tapestry-protected annihilation reaches here like any other under the
     * default rule, since the protection only keeps the chain from breaking if the paradox cascades; under the
     * absorbing rule the saga has already cleared the link, so no conflict is found (see {@code WeaverChainSaga}).
     */
    private static List<DetectedParadox> detectChainConflict(
            UUID eventId, List<Outcome> outcomes, List<WeaverChain> chains) {
        if (eventId == null || chains == null || chains.isEmpty()) {
            return List.of();
        }
        Set<UUID> annihilatedOutcomeIds = outcomes.stream()
                .filter(Outcome::annihilated)
                .map(Outcome::outcomeId)
                .collect(Collectors.toSet());
        return chains.stream()
                .filter(chain -> hasAnnihilatedPendingLink(chain, eventId, annihilatedOutcomeIds))
                .map(chain -> {
                    var pending = chain.pendingLink();
                    return new DetectedParadox(
                            ParadoxType.CHAIN_CONFLICT,
                            List.of(pending.outcomeId()),
                            "Weaver chain's pending link requires outcome " + pending.outcomeId() + " for event "
                                    + eventId + ", which was annihilated");
                })
                .toList();
    }

    private static boolean hasAnnihilatedPendingLink(WeaverChain chain, UUID eventId, Set<UUID> annihilatedOutcomeIds) {
        if (chain == null || chain.status() != ChainStatus.ACTIVE) {
            return false;
        }
        var pending = chain.pendingLink();
        return pending != null
                && eventId.equals(pending.eventId())
                && annihilatedOutcomeIds.contains(pending.outcomeId());
    }
}
