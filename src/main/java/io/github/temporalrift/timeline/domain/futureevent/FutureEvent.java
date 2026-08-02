package io.github.temporalrift.timeline.domain.futureevent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;

/**
 * Event-sourced aggregate for a single drawn event. Rebuilt by {@link #replay(UUID, List)}, never loaded
 * from a current-state row (developer-notes.md §7).
 */
public final class FutureEvent {

    private final UUID id;
    private List<Outcome> outcomes;
    private boolean resolved;

    private FutureEvent(UUID id, List<Outcome> outcomes, boolean resolved) {
        this.id = id;
        this.outcomes = outcomes;
        this.resolved = resolved;
    }

    /** Rebuilds this aggregate by replaying its domain-event stream in order. */
    public static FutureEvent replay(UUID id, List<Object> history) {
        FutureEvent state = null;
        for (var event : history) {
            if (event instanceof FutureEventDrafted && state != null) {
                throw new IllegalStateException("FutureEventDrafted replayed after initialization for " + id);
            }
            if ((event instanceof OutcomeApplied || event instanceof ProbabilityShifted)
                    && (state == null || state.resolved())) {
                throw new IllegalStateException("Event replayed outside the drafted and unresolved state for " + id);
            }
            state = switch (event) {
                case FutureEventDrafted e -> new FutureEvent(id, e.outcomes(), false);
                case OutcomeApplied e -> new FutureEvent(id, e.finalOutcomes(), true);
                case ProbabilityShifted e -> new FutureEvent(id, e.outcomes(), false);
                default -> throw new IllegalArgumentException("Unknown FutureEvent domain event: " + event.getClass());
            };
        }
        if (state == null) {
            throw new FutureEventNotFoundException(id);
        }
        return state;
    }

    /**
     * Resolves this event by selecting the highest-probability outcome, tie-broken by the smallest
     * {@code outcomeId} (natural UUID ordering) — no card, special-action, or paradox logic.
     */
    public OutcomeApplied resolve(UUID gameId, int eraNumber) {
        if (resolved) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        var winner = outcomes.stream()
                .max(Comparator.comparingInt(Outcome::probability)
                        .thenComparing(Comparator.comparing(Outcome::outcomeId).reversed()))
                .orElseThrow(() -> new IllegalStateException("FutureEvent " + id + " has no outcomes"));
        var event = new OutcomeApplied(gameId, eraNumber, id, winner.outcomeId(), outcomes);
        this.outcomes = event.finalOutcomes();
        this.resolved = true;
        return event;
    }

    /**
     * Applies a probability-shifter card's effect (GDD §4.2: sum-100, floor/ceiling, proportional
     * redistribution for single-outcome shifts). {@code magnitude} is the configured shift for the given
     * {@link ProbabilityShift} variant, resolved by the caller via {@code ProbabilityRulesPort} — this
     * aggregate stays free of any config/port coupling.
     */
    public ProbabilityShifted applyShift(ProbabilityShift shift, int magnitude, int floor, int ceiling) {
        if (resolved) {
            throw new FutureEventAlreadyResolvedException(id);
        }
        var shiftedOutcomes =
                switch (shift) {
                    case ProbabilityShift.Push p -> shiftSingle(p.targetOutcomeId(), magnitude, floor, ceiling);
                    case ProbabilityShift.Suppress s -> shiftSingle(s.targetOutcomeId(), magnitude, floor, ceiling);
                    case ProbabilityShift.Swing sw ->
                        swing(sw.sourceOutcomeId(), sw.targetOutcomeId(), magnitude, floor, ceiling);
                };
        var event = new ProbabilityShifted(id, shiftedOutcomes);
        this.outcomes = shiftedOutcomes;
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

        int desiredTarget = clamp(target.probability() + delta, floor, ceiling);
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

    /** Direct transfer between two named outcomes; the third outcome is untouched (GDD §3 Swing). */
    private List<Outcome> swing(UUID sourceOutcomeId, UUID targetOutcomeId, int magnitude, int floor, int ceiling) {
        if (Objects.equals(sourceOutcomeId, targetOutcomeId)) {
            throw new IllegalArgumentException("SWING requires distinct source and target outcomes");
        }
        var source = outcomeById(sourceOutcomeId);
        var target = outcomeById(targetOutcomeId);

        int actualMove = Math.max(
                0, Math.min(magnitude, Math.min(source.probability() - floor, ceiling - target.probability())));

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
                        o.outcomeId(), o.description(), newProbabilities.getOrDefault(o.outcomeId(), o.probability())))
                .toList();
    }

    /**
     * Splits a fixed {@code a + b} between two box-constrained values in one step: each variable's bound
     * accounts for the other's constraint (e.g. {@code a}'s lower bound is raised to {@code sum - ceiling}
     * so {@code b = sum - a} cannot exceed the ceiling), so both results land in {@code [floor, ceiling]}
     * with no further iteration — valid whenever {@code sum} itself is within {@code [2*floor, 2*ceiling]}.
     * Here {@code sum = 100 - desiredTarget} for a {@code desiredTarget} already clamped to
     * {@code [floor, ceiling]}, so the precondition holds for every possible {@code desiredTarget} exactly
     * when {@code floor + 2*ceiling >= 100 and 2*floor + ceiling <= 100} — enforced at startup by
     * {@code TimelineRulesProperties}, not re-checked here.
     */
    private static int[] clampPairPreservingSum(int a, int b, int floor, int ceiling) {
        int sum = a + b;
        int lower = Math.max(floor, sum - ceiling);
        int upper = Math.min(ceiling, sum - floor);
        int clampedA = clamp(a, lower, upper);
        return new int[] {clampedA, sum - clampedA};
    }

    private static int clamp(int value, int floor, int ceiling) {
        return Math.max(floor, Math.min(ceiling, value));
    }

    public UUID id() {
        return id;
    }

    public boolean resolved() {
        return resolved;
    }

    public List<Outcome> outcomes() {
        return List.copyOf(outcomes);
    }
}
