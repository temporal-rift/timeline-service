package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

public class InvalidChainLinkException extends RuntimeException {

    public InvalidChainLinkException(UUID chainId, UUID eventId, UUID outcomeId, String reason) {
        super("Invalid chain link for chain " + chainId + " event " + eventId + " outcome " + outcomeId + ": "
                + reason);
    }
}
