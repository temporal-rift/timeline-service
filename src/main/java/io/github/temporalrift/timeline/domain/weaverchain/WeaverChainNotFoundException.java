package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

public class WeaverChainNotFoundException extends RuntimeException {

    public WeaverChainNotFoundException(UUID chainId) {
        super("WeaverChain " + chainId + " not found");
    }
}
