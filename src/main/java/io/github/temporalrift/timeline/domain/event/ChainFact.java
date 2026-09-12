package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Non-opening facts of a Weaver chain stream. Separate from {@link WeaverChainEvent} so reconstruction applies
 * links and terminal facts without ever routing an opening fact into the applier.
 */
public sealed interface ChainFact extends WeaverChainEvent
        permits ChainLinkAdded, ChainCompleted, ChainBroken, ChainLinkInvalidated {

    /** The chain this fact belongs to, checked against the stream owner on every replayed fact. */
    UUID chainId();
}
