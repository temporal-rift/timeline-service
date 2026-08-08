package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

/** The composite natural key shared by every round-scoped coordination table. */
record RoundKey(UUID gameId, int eraNumber, int roundNumber) {}
