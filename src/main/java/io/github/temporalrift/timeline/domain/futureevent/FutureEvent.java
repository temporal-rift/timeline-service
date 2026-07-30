package io.github.temporalrift.timeline.domain.futureevent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;

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
            state = switch (event) {
                case FutureEventDrafted e -> new FutureEvent(id, e.outcomes(), false);
                case OutcomeApplied e -> {
                    requireDrafted(state, id);
                    yield new FutureEvent(id, e.finalOutcomes(), true);
                }
                default -> throw new IllegalArgumentException("Unknown FutureEvent domain event: " + event.getClass());
            };
        }
        if (state == null) {
            throw new FutureEventNotFoundException(id);
        }
        return state;
    }

    private static void requireDrafted(FutureEvent state, UUID id) {
        if (state == null) {
            throw new IllegalStateException("OutcomeApplied replayed before FutureEventDrafted for " + id);
        }
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
