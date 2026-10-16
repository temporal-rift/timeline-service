package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.UUID;

/** The unique key of a {@code game_membership} row. */
record MembershipKey(UUID gameId, UUID playerId) {}
