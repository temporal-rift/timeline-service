package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

public class WeaverChainCompletedException extends RuntimeException {

    public WeaverChainCompletedException(UUID chainId) {
        super("WeaverChain " + chainId + " is already completed");
    }
}
