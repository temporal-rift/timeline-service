package io.github.temporalrift.timeline.application.port.in;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;

/**
 * Opens a {@code ParadoxResolutionSaga} phase for the paradoxes a resolution cycle just detected. Idempotent by
 * {@code (gameId, eraNumber)} — a phase already open for that era is left untouched instead of a duplicate being
 * created (design.md Decision: "persist saga state before emitting terminal facts so recovery cannot produce
 * duplicates").
 */
public interface OpenParadoxResolutionPhaseUseCase {

    /**
     * @return the phase's authoritative pending paradoxes — always use these ids (not the ones passed in) when
     *     publishing {@code ParadoxDetected}, since a duplicate/redelivered call's own ids are discarded when a
     *     phase already existed for this era.
     */
    List<PendingParadox> open(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions);
}
