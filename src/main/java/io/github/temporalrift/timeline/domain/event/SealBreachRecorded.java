package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Event-sourced fact: a card targeted one of this {@code FutureEvent}'s sealed outcomes, so no probability
 * changed and the breach is recorded instead (paradox-detection input for MVP 6).
 */
public record SealBreachRecorded(UUID eventId) {}
