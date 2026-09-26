package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Historical event-sourced fact retained so older event streams remain replayable. It no longer changes
 * aggregate state or triggers a paradox.
 */
public record SealBreachRecorded(UUID eventId) {}
