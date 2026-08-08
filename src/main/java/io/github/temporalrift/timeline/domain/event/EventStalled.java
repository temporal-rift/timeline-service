package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/** Internal, event-sourced fact — not published externally. A {@code STALL} card marked this event stalled. */
public record EventStalled(UUID eventId) {}
