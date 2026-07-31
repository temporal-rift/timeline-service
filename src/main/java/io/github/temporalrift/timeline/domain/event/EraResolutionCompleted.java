package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

/**
 * Terminal-results barrier for an era: published once, after every {@code OutcomeApplied} for the era is
 * durably in the outbox, with entries in {@code EventsDrawn.events} reveal order (event-schema.md §3.4).
 */
public record EraResolutionCompleted(UUID gameId, int eraNumber, List<TerminalResolution> terminalResolutions) {

    public EraResolutionCompleted {
        terminalResolutions = List.copyOf(terminalResolutions);
    }
}
