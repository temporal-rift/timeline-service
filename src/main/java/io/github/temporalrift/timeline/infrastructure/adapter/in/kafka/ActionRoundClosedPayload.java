package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/** Mirrors {@code action-event}'s {@code ActionRoundClosedPayload} schema (event-schema.md §3.3). */
record ActionRoundClosedPayload(UUID gameId, int eraNumber, int roundNumber) {}
