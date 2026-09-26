package io.github.temporalrift.timeline.domain.futureevent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.EraStateCleared;
import io.github.temporalrift.timeline.domain.event.EventStalled;
import io.github.temporalrift.timeline.domain.event.EventUnstalled;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeAnnihilated;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.OutcomeSealed;
import io.github.temporalrift.timeline.domain.event.OutcomesCollided;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;
import io.github.temporalrift.timeline.domain.event.SealBreachRecorded;

/**
 * Event-sourced aggregate for a single drawn event. Rebuilt by {@link #replay(UUID, List)}, never loaded
 * from a current-state row.
 */
public final class FutureEvent {

    private final UUID id;
    private List<Outcome> outcomes;
    private Resolution resolution;
    private boolean stalled;
    private List<CollidedPair> collidedPairs;

    private FutureEvent(
            UUID id, List<Outcome> outcomes, Resolution resolution, boolean stalled, List<CollidedPair> collidedPairs) {
        this.id = id;
        this.outcomes = outcomes;
        this.resolution = resolution;
        this.stalled = stalled;
        this.collidedPairs = List.copyOf(collidedPairs);
    }

    /** Rebuilds this aggregate by replaying its domain-event stream in order. */
    public static FutureEvent replay(UUID id, List<Object> history) {
        FutureEvent state = null;
        for (var event : history) {
            if (event instanceof FutureEventDrafted && state != null) {
                throw new IllegalStateException("FutureEventDrafted replayed after initialization for " + id);
            }
            if ((event instanceof OutcomeApplied
                            || event instanceof ProbabilityShifted
                            || event instanceof OutcomesCollided
                            || event instanceof EventStalled
                            || event instanceof EventUnstalled
                            || event instanceof OutcomeSealed
                            || event instanceof OutcomeAnnihilated
                            || event instanceof SealBreachRecorded
                            || event instanceof EraStateCleared)
                    && (state == null || state.resolved())) {
                throw new IllegalStateException("Event replayed outside the drafted and unresolved state for " + id);
            }
            state = switch (event) {
                case FutureEventDrafted e -> new FutureEvent(id, e.outcomes(), null, false, List.of());
                case OutcomeApplied e ->
                    new FutureEvent(
                            id,
                            e.finalOutcomes(),
                            new Resolution(e.winningOutcomeId(), e.eraNumber()),
                            false,
                            state.collidedPairs());
                case ProbabilityShifted e -> state.withOutcomes(e.outcomes());
                case OutcomesCollided e -> {
                    var pairs = new ArrayList<>(state.collidedPairs());
                    pairs.add(new CollidedPair(e.firstOutcomeId(), e.secondOutcomeId()));
                    yield new FutureEvent(id, e.outcomes(), null, state.stalled(), pairs);
                }
                case EventStalled _ -> new FutureEvent(id, state.outcomes(), null, true, state.collidedPairs());
                case EventUnstalled _ -> new FutureEvent(id, state.outcomes(), null, false, state.collidedPairs());
                case OutcomeSealed e -> state.withOutcomes(e.outcomes());
                case OutcomeAnnihilated e -> state.withOutcomes(e.outcomes());
                case SealBreachRecorded _ -> state;
                case EraStateCleared e -> new FutureEvent(id, e.outcomes(), null, state.stalled(), List.of());
                default -> throw new IllegalArgumentException("Unknown FutureEvent domain event: " + event.getClass());
            };
        }
        if (state == null) {
            throw new FutureEventNotFoundException(id);
        }
        return state;
    }

    private FutureEvent withOutcomes(List<Outcome> replayedOutcomes) {
        return new FutureEvent(id, replayedOutcomes, null, stalled, collidedPairs);
    }

    /**
     * Marks this event stalled ({@code STALL}): excluded from resolution this era, carried into the next
     * era's index instead.
     */
    public EventStalled markStalled() {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        this.stalled = true;
        return new EventStalled(id);
    }

    /** Clears a stalled state ({@code NULLIFY} targeting the {@code STALL} that set it). */
    public EventUnstalled clearStalled() {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        this.stalled = false;
        return new EventUnstalled(id);
    }

    /**
     * Resolves this event by drawing the winning outcome at random from its non-annihilated outcomes, weighted by
     * each one's current probability — no outcome is guaranteed regardless of its lead, matching the documented
     * 0-90% probability model. {@code roll} is an already-generated random value supplied by the caller (this
     * aggregate stays free of any randomness-port coupling, mirroring how {@link #applyShift} takes
     * caller-resolved magnitude/floor/ceiling instead of a port reference); it is folded into {@code [0, total)}
     * via {@link Math#floorMod} against the sum of eligible weights, then walked cumulatively. An annihilated
     * outcome contributes no weight and can never win. Tied outcomes, including a Dead Heat cleared by Stabilize, are
     * equally likely. Callers must check {@link #hasDrawableWeight()} first.
     */
    public OutcomeApplied resolve(UUID gameId, int eraNumber, long roll) {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        if (stalled) {
            throw new FutureEventStalledException(id);
        }
        var winner = drawWeighted(roll);
        var event = new OutcomeApplied(gameId, eraNumber, id, winner.outcomeId(), outcomes);
        this.outcomes = event.finalOutcomes();
        this.resolution = new Resolution(winner.outcomeId(), eraNumber);
        return event;
    }

    /**
     * Cumulative-weight walk over the non-annihilated outcomes. A zero eligible total is {@code IMPOSSIBLE_ERASURE}
     * and never reaches here, so the defensive exceptions below are not expected runtime paths.
     */
    private Outcome drawWeighted(long roll) {
        var eligible = outcomes.stream().filter(o -> !o.annihilated()).toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("FutureEvent " + id + " has no eligible outcomes");
        }
        int total = eligible.stream().mapToInt(Outcome::probability).sum();
        if (total <= 0) {
            throw new IllegalStateException("FutureEvent " + id + " has no positive weight among eligible outcomes");
        }
        long normalizedRoll = Math.floorMod(roll, total);
        int cumulative = 0;
        for (var outcome : eligible) {
            cumulative += outcome.probability();
            if (normalizedRoll < cumulative) {
                return outcome;
            }
        }
        throw new IllegalStateException("FutureEvent " + id + " weighted draw did not resolve a winner");
    }

    /**
     * Applies a probability-shifter card's effect (sum-100, floor/ceiling, proportional
     * redistribution for single-outcome shifts). {@code magnitude} is the configured shift for the given
     * {@link ProbabilityShift} variant, resolved by the caller via {@code ProbabilityRulesPort} — this
     * aggregate stays free of any config/port coupling.
     *
     * <p>Returns a {@link ProbabilityShifted} with the post-shift outcomes, or an {@link OutcomesCollided} for a
     * {@code COLLIDE} that left its pair equal: a shift that would have
     * to move a sealed outcome's probability is declined with weights unchanged as an ordinary failure (see
     * {@link #sealOutcome}) — callers only need the persisted fact, mirroring
     * {@code FutureEventRepository#append}'s own {@code Object} domain-event parameter.
     */
    public Object applyShift(ProbabilityShift shift, int magnitude, int floor, int ceiling) {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        return switch (shift) {
            case ProbabilityShift.Push(var targetOutcomeId) ->
                shiftSingleOrDecline(targetOutcomeId, magnitude, floor, ceiling);
            case ProbabilityShift.Suppress(var targetOutcomeId) ->
                shiftSingleOrDecline(targetOutcomeId, magnitude, floor, ceiling);
            case ProbabilityShift.Swing(var sourceOutcomeId, var targetOutcomeId) ->
                swingOrDecline(sourceOutcomeId, targetOutcomeId, magnitude, floor, ceiling);
            case ProbabilityShift.Collide(var outcomeAId, var outcomeBId) ->
                collideOrDecline(outcomeAId, outcomeBId, floor, ceiling);
            case ProbabilityShift.Restore(var targetProbabilities) -> {
                // A snapshot predates any SEAL cast on this event since — restoring it verbatim would
                // silently overwrite a sealed outcome's now-frozen probability if the two disagree. Decline
                // the whole restore rather than partially rebuild the other two around a value we're not
                // allowed to change (undo/REDIRECT/CORRUPT all funnel through here, so this protects all
                // three, not just CORRUPT) — an ordinary failure with weights unchanged.
                if (conflictsWithSealedOutcome(targetProbabilities)) {
                    yield unchanged();
                }
                var shiftedOutcomes = replaceProbabilities(targetProbabilities);
                var event = new ProbabilityShifted(id, shiftedOutcomes);
                this.outcomes = shiftedOutcomes;
                yield event;
            }
        };
    }

    /**
     * Resolves one round's probability effects on this event simultaneously: each shift is evaluated alone against
     * the current weights under {@link #applyShift}'s rules, the resulting weight changes are summed, and the sum is
     * fitted once into {@code [floor, ceiling]}. Input order never affects the result. Identical {@code COLLIDE}
     * pairs count once, and a collided pair is recorded only if it is still equal after the combined result.
     */
    public SimultaneousShiftResult applySimultaneousShifts(List<SimultaneousShift> shifts, int floor, int ceiling) {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        var start = outcomes;
        var combined = new LinkedHashMap<UUID, Integer>();
        start.forEach(o -> combined.put(o.outcomeId(), o.probability()));
        var countedCollides = new HashSet<Set<UUID>>();
        var equalizedPairs = new ArrayList<CollidedPair>();
        var movedAlone = new ArrayList<Boolean>();
        for (var simultaneous : shifts) {
            var scratch = new FutureEvent(id, start, null, stalled, collidedPairs);
            var aloneResult = scratch.applyShift(simultaneous.shift(), simultaneous.magnitude(), floor, ceiling);
            movedAlone.add(!scratch.outcomes.equals(start));
            if (simultaneous.shift() instanceof ProbabilityShift.Collide(var a, var b)
                    && !countedCollides.add(Set.of(a, b))) {
                continue;
            }
            scratch.outcomes.forEach(o -> combined.merge(o.outcomeId(), o.probability(), Integer::sum));
            start.forEach(o -> combined.merge(o.outcomeId(), -o.probability(), Integer::sum));
            if (aloneResult instanceof OutcomesCollided collided) {
                equalizedPairs.add(new CollidedPair(collided.firstOutcomeId(), collided.secondOutcomeId()));
            }
        }
        this.outcomes = replaceProbabilities(fitWithinBounds(start, combined, floor, ceiling));
        var events = new ArrayList<Object>();
        events.add(new ProbabilityShifted(id, outcomes));
        var pairs = new ArrayList<>(collidedPairs);
        for (var pair : equalizedPairs) {
            if (outcomeById(pair.firstOutcomeId()).probability()
                    == outcomeById(pair.secondOutcomeId()).probability()) {
                pairs.add(pair);
                events.add(new OutcomesCollided(id, outcomes, pair.firstOutcomeId(), pair.secondOutcomeId()));
            }
        }
        this.collidedPairs = List.copyOf(pairs);
        return new SimultaneousShiftResult(events, movedAlone);
    }

    /**
     * Integer water-filling: every unsealed weight moves by one common offset, clamped to its bounds, so the
     * unsealed total is preserved; points left over by integer rounding come from outcomes still movable, in
     * declared outcome order. Each outcome's bounds widen to include its starting weight, so the starting state is
     * always feasible and a weight that began outside {@code [floor, ceiling]} is never pushed further than it was.
     * Weights already in bounds are returned unchanged.
     */
    private Map<UUID, Integer> fitWithinBounds(
            List<Outcome> start, Map<UUID, Integer> combined, int floor, int ceiling) {
        var bounds = new LinkedHashMap<UUID, int[]>();
        start.stream()
                .filter(o -> !o.sealed())
                .forEach(o -> bounds.put(
                        o.outcomeId(),
                        new int[] {Math.min(floor, o.probability()), Math.max(ceiling, o.probability())}));
        if (bounds.entrySet().stream().allMatch(bound -> {
            int probability = combined.get(bound.getKey());
            return probability >= bound.getValue()[0] && probability <= bound.getValue()[1];
        })) {
            return combined;
        }
        int freeTotal = bounds.keySet().stream().mapToInt(combined::get).sum();
        int offset = bounds.entrySet().stream()
                .mapToInt(bound -> combined.get(bound.getKey()) - bound.getValue()[0])
                .max()
                .orElseThrow();
        while (clampedTotal(combined, bounds, offset) < freeTotal) {
            offset--;
        }
        var fitted = new LinkedHashMap<>(combined);
        int surplus = clampedTotal(combined, bounds, offset) - freeTotal;
        for (var bound : bounds.entrySet()) {
            int shifted = combined.get(bound.getKey()) - offset;
            int value = Math.clamp(shifted, bound.getValue()[0], bound.getValue()[1]);
            if (surplus > 0 && shifted > bound.getValue()[0] && shifted <= bound.getValue()[1]) {
                value--;
                surplus--;
            }
            fitted.put(bound.getKey(), value);
        }
        return fitted;
    }

    private static int clampedTotal(Map<UUID, Integer> combined, Map<UUID, int[]> bounds, int offset) {
        return bounds.entrySet().stream()
                .mapToInt(bound -> Math.clamp(
                        (long) combined.get(bound.getKey()) - offset, bound.getValue()[0], bound.getValue()[1]))
                .sum();
    }

    private boolean conflictsWithSealedOutcome(Map<UUID, Integer> targetProbabilities) {
        return outcomes.stream()
                .anyMatch(o -> o.sealed()
                        && targetProbabilities.containsKey(o.outcomeId())
                        && targetProbabilities.get(o.outcomeId()) != o.probability());
    }

    /**
     * PUSH/SUPPRESS target the named outcome; if it's sealed, the shift is declined with weights unchanged
     * as an ordinary failure. Otherwise its movement must be redistributed into the other two outcomes —
     * but a sealed "other" can't absorb any of it either, so: both others sealed → nowhere to put the
     * movement, declined unchanged; exactly one sealed → the sole unsealed other absorbs all of it;
     * neither sealed → the existing proportional 3-way split. A fully clamped zero move that touches no
     * sealed weight is likewise an ordinary no-op.
     */
    private Object shiftSingleOrDecline(UUID targetOutcomeId, int magnitude, int floor, int ceiling) {
        var target = outcomeById(targetOutcomeId);
        if (target.sealed()) {
            return unchanged();
        }
        var others = outcomes.stream()
                .filter(o -> !o.outcomeId().equals(targetOutcomeId))
                .toList();
        var other1 = others.get(0);
        var other2 = others.get(1);

        int desiredTarget = target.probability() + magnitude;
        int actualDelta = Math.clamp(desiredTarget, floor, ceiling) - target.probability();
        if (actualDelta == 0) {
            var event = new ProbabilityShifted(id, outcomes());
            this.outcomes = event.outcomes();
            return event;
        }

        List<Outcome> shiftedOutcomes;
        if (other1.sealed() && other2.sealed()) {
            return unchanged();
        } else if (other1.sealed() || other2.sealed()) {
            var freeOther = other1.sealed() ? other2 : other1;
            var rebalanced = clampPairPreservingSum(
                    target.probability() + magnitude, freeOther.probability() - magnitude, floor, ceiling);
            // The sealed other is deliberately omitted from the map — replaceProbabilities keeps it unchanged.
            shiftedOutcomes =
                    replaceProbabilities(Map.of(targetOutcomeId, rebalanced[0], freeOther.outcomeId(), rebalanced[1]));
        } else {
            shiftedOutcomes = shiftSingle(targetOutcomeId, magnitude, floor, ceiling);
        }
        var event = new ProbabilityShifted(id, shiftedOutcomes);
        this.outcomes = shiftedOutcomes;
        return event;
    }

    private Object swingOrDecline(UUID sourceOutcomeId, UUID targetOutcomeId, int magnitude, int floor, int ceiling) {
        if (Objects.equals(sourceOutcomeId, targetOutcomeId)) {
            throw new IllegalArgumentException("SWING requires distinct source and target outcomes");
        }
        var source = outcomeById(sourceOutcomeId);
        var target = outcomeById(targetOutcomeId);
        if (source.sealed() || target.sealed()) {
            return unchanged();
        }
        var shiftedOutcomes = swing(sourceOutcomeId, targetOutcomeId, magnitude, floor, ceiling);
        var event = new ProbabilityShifted(id, shiftedOutcomes);
        this.outcomes = shiftedOutcomes;
        return event;
    }

    /**
     * Sets both named outcomes to the integer floor of their combined midpoint and moves any one-point remainder
     * to the third outcome, preserving the 100 total within floor/ceiling; a sealed selection or a remainder owed
     * to a sealed third declines the shift with weights unchanged as an ordinary failure, and a bound-overflow
     * edge keeps total and bounds via a deterministic outcome-id-ordered fallback without guaranteeing equality.
     * A result that leaves the pair equal is an {@link OutcomesCollided}, the Dead Heat trigger input.
     */
    private Object collideOrDecline(UUID outcomeAId, UUID outcomeBId, int floor, int ceiling) {
        if (Objects.equals(outcomeAId, outcomeBId)) {
            throw new IllegalArgumentException("COLLIDE requires distinct outcomes");
        }
        var a = outcomeById(outcomeAId);
        var b = outcomeById(outcomeBId);
        if (a.sealed() || b.sealed()) {
            return unchanged();
        }
        var thirds = outcomes.stream()
                .filter(o -> !o.outcomeId().equals(outcomeAId) && !o.outcomeId().equals(outcomeBId))
                .toList();
        if (thirds.size() != 1) {
            int combinedFallback = a.probability() + b.probability();
            int halfFallback = combinedFallback / 2;
            var rebalancedFallback =
                    clampPairPreservingSum(halfFallback, combinedFallback - halfFallback, floor, ceiling);
            var fallbackOutcomes =
                    replaceProbabilities(Map.of(outcomeAId, rebalancedFallback[0], outcomeBId, rebalancedFallback[1]));
            return collisionResult(outcomeAId, outcomeBId, fallbackOutcomes);
        }
        var third = thirds.getFirst();
        int combined = a.probability() + b.probability();
        int mid = combined / 2;
        int remainder = combined % 2;
        if (remainder == 1 && third.sealed()) {
            return unchanged();
        }
        int thirdIdeal = third.probability() + remainder;
        boolean pairFeasible = mid >= floor && mid <= ceiling;
        boolean thirdFeasible = thirdIdeal >= floor && thirdIdeal <= ceiling;
        if (pairFeasible && thirdFeasible) {
            var rebalanced = clampPairPreservingSum(mid, mid, floor, ceiling);
            var shiftedOutcomes = replaceProbabilities(
                    Map.of(outcomeAId, rebalanced[0], outcomeBId, rebalanced[1], third.outcomeId(), thirdIdeal));
            return collisionResult(outcomeAId, outcomeBId, shiftedOutcomes);
        }
        int clampedThird = Math.clamp(thirdIdeal, floor, ceiling);
        int pairSum = 100 - clampedThird;
        var ordered = outcomeAId.compareTo(outcomeBId) <= 0
                ? new UUID[] {outcomeAId, outcomeBId}
                : new UUID[] {outcomeBId, outcomeAId};
        int firstIdeal = (pairSum + 1) / 2;
        int secondIdeal = pairSum - firstIdeal;
        var rebalanced = clampPairPreservingSum(firstIdeal, secondIdeal, floor, ceiling);
        var shiftedOutcomes = replaceProbabilities(
                Map.of(ordered[0], rebalanced[0], ordered[1], rebalanced[1], third.outcomeId(), clampedThird));
        return collisionResult(outcomeAId, outcomeBId, shiftedOutcomes);
    }

    /** Marks the pair collided only when the applied result actually left it equal. */
    private Object collisionResult(UUID outcomeAId, UUID outcomeBId, List<Outcome> shiftedOutcomes) {
        this.outcomes = shiftedOutcomes;
        if (outcomeById(outcomeAId).probability() != outcomeById(outcomeBId).probability()) {
            return new ProbabilityShifted(id, shiftedOutcomes);
        }
        var pairs = new ArrayList<>(collidedPairs);
        pairs.add(new CollidedPair(outcomeAId, outcomeBId));
        this.collidedPairs = List.copyOf(pairs);
        return new OutcomesCollided(id, shiftedOutcomes, outcomeAId, outcomeBId);
    }

    /**
     * A shift declined for seal reasons returns the current outcomes unchanged (the same ordinary-failure
     * shape as a fully clamped zero move).
     */
    private ProbabilityShifted unchanged() {
        return new ProbabilityShifted(id, outcomes());
    }

    /**
     * Locks {@code outcomeId} ({@code SEAL}) through the end of the era: the sealed outcome's probability
     * cannot move. A later shift that would have to move it is declined with weights unchanged as an
     * ordinary failure — no paradox. No legal effect changes a sealed outcome's probability.
     */
    public OutcomeSealed sealOutcome(UUID outcomeId) {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        outcomeById(outcomeId);
        var updated = outcomes.stream()
                .map(o -> o.outcomeId().equals(outcomeId)
                        ? new Outcome(o.outcomeId(), o.description(), o.probability(), true, o.annihilated())
                        : o)
                .toList();
        var event = new OutcomeSealed(id, updated);
        this.outcomes = updated;
        return event;
    }

    /**
     * Clears this event's per-era state — every outcome's sealed/annihilated flag and the collided pairs —
     * when it carries into a new era. Identity, outcome set, and
     * probabilities are preserved; only the flags a new era must not inherit are cleared.
     */
    public EraStateCleared clearEraState() {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        var cleared = outcomes.stream()
                .map(o -> new Outcome(o.outcomeId(), o.description(), o.probability(), false, false))
                .toList();
        var event = new EraStateCleared(id, cleared);
        this.outcomes = cleared;
        this.collidedPairs = List.of();
        return event;
    }

    /** Marks {@code outcomeId} annihilated ({@code ANNIHILATE}); it can never win this event's resolution. */
    public OutcomeAnnihilated annihilateOutcome(UUID outcomeId) {
        if (resolved()) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        outcomeById(outcomeId);
        var updated = outcomes.stream()
                .map(o -> o.outcomeId().equals(outcomeId)
                        ? new Outcome(o.outcomeId(), o.description(), o.probability(), o.sealed(), true)
                        : o)
                .toList();
        var event = new OutcomeAnnihilated(id, updated);
        this.outcomes = updated;
        return event;
    }

    /** Single-outcome shift: clamp the target, redistribute the actual delta across the other two proportionally. */
    private List<Outcome> shiftSingle(UUID targetOutcomeId, int delta, int floor, int ceiling) {
        var target = outcomeById(targetOutcomeId);
        var others = outcomes.stream()
                .filter(o -> !o.outcomeId().equals(targetOutcomeId))
                .toList();
        var other1 = others.get(0);
        var other2 = others.get(1);

        int unclampedTarget = target.probability() + delta;
        int desiredTarget = Math.clamp(unclampedTarget, floor, ceiling);
        int actualDelta = desiredTarget - target.probability();
        if (actualDelta == 0) {
            return outcomes();
        }

        int remaining = -actualDelta;
        int othersTotal = other1.probability() + other2.probability();
        int share1 = othersTotal == 0
                ? remaining / 2
                : (int) Math.round(remaining * (double) other1.probability() / othersTotal);
        int share2 = remaining - share1;

        var rebalanced =
                clampPairPreservingSum(other1.probability() + share1, other2.probability() + share2, floor, ceiling);

        return replaceProbabilities(Map.of(
                targetOutcomeId, desiredTarget, other1.outcomeId(), rebalanced[0], other2.outcomeId(), rebalanced[1]));
    }

    /** Direct transfer between two named outcomes; the third outcome is untouched (SWING). */
    private List<Outcome> swing(UUID sourceOutcomeId, UUID targetOutcomeId, int magnitude, int floor, int ceiling) {
        if (Objects.equals(sourceOutcomeId, targetOutcomeId)) {
            throw new IllegalArgumentException("SWING requires distinct source and target outcomes");
        }
        var source = outcomeById(sourceOutcomeId);
        var target = outcomeById(targetOutcomeId);

        int actualMove =
                Math.clamp(Math.min(source.probability() - floor, ceiling - target.probability()), 0, magnitude);

        return replaceProbabilities(Map.of(
                sourceOutcomeId,
                source.probability() - actualMove,
                targetOutcomeId,
                target.probability() + actualMove));
    }

    private Outcome outcomeById(UUID outcomeId) {
        return outcomes.stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow(() -> new UnknownOutcomeException(id, outcomeId));
    }

    private List<Outcome> replaceProbabilities(Map<UUID, Integer> newProbabilities) {
        return outcomes.stream()
                .map(o -> new Outcome(
                        o.outcomeId(),
                        o.description(),
                        newProbabilities.getOrDefault(o.outcomeId(), o.probability()),
                        o.sealed(),
                        o.annihilated()))
                .toList();
    }

    /**
     * Splits fixed {@code desiredFirst + desiredSecond} between two box-constrained values in one step: each
     * variable's bound accounts for the other's constraint (e.g. the first value's lower bound is raised to
     * {@code sum - ceiling} so {@code second = sum - first} cannot exceed the ceiling), so both results land
     * in {@code [floor, ceiling]} with no further iteration — valid whenever {@code sum} itself is within
     * {@code [2*floor, 2*ceiling]}.
     * Used both for two of three outcomes given the third's already-clamped value ({@code sum = 100 -
     * thirdOutcome}), and for a target/free-other pair when the remaining third outcome is sealed and frozen
     * ({@code sum = 100 - sealedOutcome}) — either way {@code sum} is {@code 100} minus one outcome's
     * probability, already within {@code [floor, ceiling]}, so the precondition holds for every case exactly
     * when {@code floor + 2*ceiling >= 100 and 2*floor + ceiling <= 100} — enforced at startup by
     * {@code TimelineRulesProperties}, not re-checked here.
     *
     * @param desiredFirst the first outcome's desired probability before clamping
     * @param desiredSecond the second outcome's desired probability before clamping
     * @return the clamped pair {@code [first, second]} with the same sum as the inputs
     */
    private static int[] clampPairPreservingSum(int desiredFirst, int desiredSecond, int floor, int ceiling) {
        int sum = desiredFirst + desiredSecond;
        int lower = Math.max(floor, sum - ceiling);
        int upper = Math.min(ceiling, sum - floor);
        int clampedFirst = Math.clamp(desiredFirst, lower, upper);
        return new int[] {clampedFirst, sum - clampedFirst};
    }

    public UUID id() {
        return id;
    }

    public boolean resolved() {
        return resolution != null;
    }

    /** The outcome this event drew and the era it resolved in, once resolved. */
    public Optional<Resolution> resolution() {
        return Optional.ofNullable(resolution);
    }

    public boolean stalled() {
        return stalled;
    }

    /** Pairs a {@code COLLIDE} left equal this era, in application order; cleared on carry. */
    public List<CollidedPair> collidedPairs() {
        return collidedPairs;
    }

    /** True when the non-annihilated outcomes hold positive total weight — check before {@link #resolve}. */
    public boolean hasDrawableWeight() {
        return outcomes.stream()
                        .filter(o -> !o.annihilated())
                        .mapToInt(Outcome::probability)
                        .sum()
                > 0;
    }

    public List<Outcome> outcomes() {
        return List.copyOf(outcomes);
    }
}
