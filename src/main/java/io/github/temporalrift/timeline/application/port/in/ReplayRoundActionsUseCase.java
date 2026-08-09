package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/**
 * Driving port: replay a closed round's buffered {@code CardPlayed}/{@code SpecialActionPlayed} actions in
 * strict priority-tier order (sagas.md "Action Ordering Rules": {@code NULLIFY -> SEAL -> ANNIHILATE -> CORRUPT
 * -> AMPLIFY -> remaining cards by submission timestamp}). Replaces {@code ApplyProbabilityShiftUseCase},
 * {@code PlayCardModifierUseCase}, {@code PlaySpecialActionUseCase}, and {@code ResolvePendingCorruptUseCase}
 * (design.md Decision 7, timeline-mvp9-resolution-ordering-paradox-cards) — those applied each action
 * immediately on consumption; this instead reads the whole round back once its {@code ActionRoundClosed}
 * arrives and applies it in one pass.
 */
public interface ReplayRoundActionsUseCase {

    void replay(UUID gameId, int eraNumber, int roundNumber);
}
