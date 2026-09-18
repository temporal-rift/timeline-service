package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact — THREAD created a pending link naming a not-yet-resolved current-era outcome. */
public record ChainLinkThreaded(UUID chainId, UUID eventId, UUID outcomeId, int eraNumber) implements ChainFact {}
