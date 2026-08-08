package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors {@code session-event}'s {@code EraStartedPayload} schema (event-schema.md §3.2). */
record EraStartedPayload(
        UUID gameId,
        int eraNumber,
        @JsonProperty(required = true) List<UUID> carryOverEventIds,
        List<UUID> playerIds) {}
