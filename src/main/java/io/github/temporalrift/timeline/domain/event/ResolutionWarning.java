package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * A non-fatal resolution anomaly resolved deterministically (last-write-wins) rather than failing the era —
 * currently only an identical-{@code occurredAt} tie between two remaining-tier actions in the same round
 * (design.md Decision 5). Informational; not event-sourced.
 */
public record ResolutionWarning(UUID gameId, int eraNumber, int roundNumber, String detail) {}
