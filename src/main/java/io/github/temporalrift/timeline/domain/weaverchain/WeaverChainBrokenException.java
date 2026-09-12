package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

public class WeaverChainBrokenException extends RuntimeException {

    public WeaverChainBrokenException(UUID chainId) {
        super("WeaverChain " + chainId + " is broken");
    }
}
