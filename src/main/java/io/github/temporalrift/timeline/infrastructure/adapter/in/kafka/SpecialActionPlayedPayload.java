package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/** Mirrors {@code action-event}'s {@code SpecialActionPlayedPayload} schema (event-schema.md §3.3). */
record SpecialActionPlayedPayload(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID playerId,
        String specialAction,
        UUID targetEventId,
        UUID targetOutcomeId,
        UUID targetPlayerId) {}
