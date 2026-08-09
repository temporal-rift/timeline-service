package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.UUID;

/**
 * Mirrors the documented {@code ParadoxResolutionCardPlayed} schema (event-schema.md §3.4). No published
 * {@code game.events} contract module defines this yet — game-service must add one (temporal-rift/game-service#110,
 * non-blocking) — so this is hand-rolled against the documented shape, exactly like {@link CardPlayedPayload} is
 * against {@code action-event} despite this service holding no compile-time dependency on that module either.
 */
record ParadoxResolutionCardPlayedPayload(
        UUID gameId,
        int eraNumber,
        UUID playerId,
        UUID cardInstanceId,
        String cardType,
        UUID targetEventId,
        UUID targetOutcomeId) {}
