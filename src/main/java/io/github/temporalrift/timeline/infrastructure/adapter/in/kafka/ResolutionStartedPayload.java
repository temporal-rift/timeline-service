package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/** Mirrors {@code session-event}'s {@code ResolutionStartedPayload} schema (event-schema.md §3.4). */
record ResolutionStartedPayload(UUID gameId, int eraNumber) {}
