package io.github.temporalrift.timeline.domain.futureevent;

/** How an accepted erasure splits the erased weight between the event's remaining eligible, unsealed outcomes. */
public sealed interface ErasureRedistribution {

    /** Shares the erased weight in proportion to the absorbing outcomes' weights. */
    static ErasureRedistribution proportional() {
        return new Proportional();
    }

    /**
     * Routes the erased weight to the event's written outcome first when it was written in {@code roundNumber} or
     * earlier; see {@link WrittenOutcomeFirst}.
     */
    static ErasureRedistribution writtenOutcomeFirst(int roundNumber) {
        return new WrittenOutcomeFirst(roundNumber);
    }

    /** Proportional sharing; every erasure falls back to it when no written-outcome routing applies. */
    record Proportional() implements ErasureRedistribution {}

    /**
     * Routes the erased weight to the event's written outcome first, up to the ceiling, and the remainder to the other
     * absorbing outcome — only when the outcome was written in {@code roundNumber} or earlier and is itself an absorber
     * (eligible, unsealed, not erased). Otherwise the split is proportional.
     *
     * @param roundNumber the action round the erasure resolves in
     */
    record WrittenOutcomeFirst(int roundNumber) implements ErasureRedistribution {}
}
