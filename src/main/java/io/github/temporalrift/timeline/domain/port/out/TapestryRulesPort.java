package io.github.temporalrift.timeline.domain.port.out;

/** Configured effect of Tapestry protection on an annihilated pending link. */
public interface TapestryRulesPort {

    /**
     * {@code true} restores the absorbing rule: consumed protection raises no {@code CHAIN_CONFLICT} and clears the
     * link at once. {@code false} (the default) keeps the link open so the paradox is raised, and only prevents the
     * chain from breaking if that paradox cascades.
     */
    boolean tapestryAbsorbsChainConflict();
}
