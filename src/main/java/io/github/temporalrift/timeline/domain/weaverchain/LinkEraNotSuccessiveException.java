package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/** A link whose era is not strictly later than the era of the link before it. */
public class LinkEraNotSuccessiveException extends InvalidChainLinkException {

    public LinkEraNotSuccessiveException(UUID chainId, UUID eventId, UUID outcomeId, int eraNumber, int previousEra) {
        super(chainId, eventId, outcomeId, "era " + eraNumber + " is not after the previous link's era " + previousEra);
    }
}
