package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.List;
import java.util.UUID;

/** Mirrors {@code session-event}'s {@code EraStartedPayload} schema (event-schema.md §3.2). */
record EraStartedPayload(UUID gameId, int eraNumber, List<UUID> cascadedEventIds, List<UUID> playerIds) {}
