package io.github.temporalrift.timeline.domain.membership;

/** Faction identity of one game participant, as carried by {@code FactionAssigned}. */
public enum MemberFaction {
    ERASERS,
    PROPHETS,
    REVISIONISTS,
    WEAVERS,
    ACTIVISTS;

    public boolean isWeaver() {
        return this == WEAVERS;
    }
}
