package io.github.temporalrift.timeline.shared;

/**
 * Ordering contract for {@code @RestControllerAdvice} beans. Advice order — not exception-type
 * specificity — decides handler lookup across advices: an advice without an explicit order ties
 * with the shared fallback and its mappings may be silently swallowed by the {@code Exception}
 * catch-all. Enforced by an ArchUnit rule in {@code ArchitectureTest}.
 */
public final class RestAdviceOrder {

    /** Every chains-scoped exception handler advice. */
    public static final int MODULE = 0;

    /**
     * Only the shared catch-all advice. Same value as {@code Ordered.LOWEST_PRECEDENCE} (kept as a
     * literal so this class stays framework-free).
     */
    public static final int GLOBAL_FALLBACK = Integer.MAX_VALUE;

    private RestAdviceOrder() {}
}
