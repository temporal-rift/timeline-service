package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/** Event-sourced fact and source of the {@code OutcomeApplied} wire event (event-schema.md §3.4). */
public record OutcomeApplied(
        UUID gameId, int eraNumber, UUID eventId, UUID winningOutcomeId, List<Outcome> finalOutcomes) {}
