package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** One of a {@link FutureEvent}'s possible results. */
public record Outcome(UUID outcomeId, String description, int probability) {}
