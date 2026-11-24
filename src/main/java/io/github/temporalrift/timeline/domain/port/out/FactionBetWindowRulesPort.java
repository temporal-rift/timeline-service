package io.github.temporalrift.timeline.domain.port.out;

/** The round every faction bet closes after, shared with game-service so both derive the same written outcomes. */
public interface FactionBetWindowRulesPort {

    /**
     * The last round, from 1 to 3, in which a bet still takes effect. A Foresight played after it is still accepted
     * but writes nothing.
     */
    int factionBetLatestRound();
}
