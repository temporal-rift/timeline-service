package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/** Mirrors {@code action-event}'s {@code CardPlayedPayload} schema (event-schema.md §3.3). */
record CardPlayedPayload(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID playerId,
        UUID cardInstanceId,
        String cardType,
        UUID targetEventId,
        UUID sourceOutcomeId,
        UUID targetOutcomeId) {}
