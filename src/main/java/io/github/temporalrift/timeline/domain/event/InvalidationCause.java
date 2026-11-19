package io.github.temporalrift.timeline.domain.event;

/** Why a Weaver chain's pending link cleared without confirming. */
public enum InvalidationCause {
    /** The linked event resolved with a winning outcome other than the linked one. */
    RESOLVED_DIFFERENTLY,
    /** The linked event stalled or cascaded, or the link expired because its era ended without the event resolving. */
    NO_WINNER,
    /** A Chain Conflict on the link cascaded or was cleared after Tapestry protection was consumed against it. */
    CHAIN_CONFLICT_CLEARED,
    /** Armed Tapestry absorbed an Annihilate against the link under the absorbing rule. */
    ANNIHILATE_ABSORBED,
    /** The game ended with the link pending. */
    GAME_ENDED
}
