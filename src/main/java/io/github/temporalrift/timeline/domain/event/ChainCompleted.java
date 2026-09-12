package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced terminal fact — the third link landed. */
public record ChainCompleted(UUID chainId) implements ChainFact {}
