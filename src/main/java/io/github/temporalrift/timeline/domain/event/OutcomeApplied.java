package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/**
 * Event-sourced fact and source of the {@code OutcomeApplied} wire event. {@code consequentialErasures} lists the
 * erased outcomes whose redistribution decided the drawn winner; a fact stored before the list existed replays with
 * an empty list.
 */
public record OutcomeApplied(
        UUID gameId,
        int eraNumber,
        UUID eventId,
        UUID winningOutcomeId,
        List<Outcome> finalOutcomes,
        List<UUID> consequentialErasures) {

    public OutcomeApplied {
        finalOutcomes = List.copyOf(finalOutcomes);
        consequentialErasures = consequentialErasures == null ? List.of() : List.copyOf(consequentialErasures);
    }
}
