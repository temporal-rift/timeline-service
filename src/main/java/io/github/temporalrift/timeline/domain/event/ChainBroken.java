package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced terminal fact — the chain was unravelled. */
public record ChainBroken(UUID chainId, String reason) {}
