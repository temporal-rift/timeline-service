package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * A paradox cleared during its resolution phase's player-submission branch (event-schema.md §3.4, GDD §6.2): the
 * affected event proceeds to normal outcome resolution this era. Not event-sourced, built and published by
 * {@code ParadoxResolutionSaga}.
 */
public record ParadoxResolved(UUID gameId, int eraNumber, UUID paradoxId, UUID resolvedByPlayerId) {}
