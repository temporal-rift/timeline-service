package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** The outcome an event drew at resolution and the era it resolved in. */
public record Resolution(UUID winningOutcomeId, int eraNumber) {}
