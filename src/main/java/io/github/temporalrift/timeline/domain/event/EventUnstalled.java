package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact — not published externally. A {@code NULLIFY} cleared this event's stalled state. */
public record EventUnstalled(UUID eventId) {}
