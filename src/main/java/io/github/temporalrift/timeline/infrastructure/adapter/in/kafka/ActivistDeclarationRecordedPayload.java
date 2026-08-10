package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/** Mirrors {@code action-event}'s {@code ActivistDeclarationRecordedPayload} schema (event-schema.md §3.3). */
record ActivistDeclarationRecordedPayload(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID playerId,
        String mode,
        UUID targetEventId,
        UUID targetOutcomeId) {}
