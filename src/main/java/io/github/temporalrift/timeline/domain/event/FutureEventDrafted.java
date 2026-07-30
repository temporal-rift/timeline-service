package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/** Internal, event-sourced fact — not published externally. */
public record FutureEventDrafted(UUID eventId, List<Outcome> outcomes) {}
