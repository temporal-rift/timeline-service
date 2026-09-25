package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/** A pending-link operation on a chain with no open pending link from the given era. */
public class NoPendingLinkException extends InvalidChainLinkException {

    public NoPendingLinkException(UUID chainId, UUID eventId, UUID outcomeId, int eraNumber) {
        super(chainId, eventId, outcomeId, "no pending link from era " + eraNumber);
    }
}
