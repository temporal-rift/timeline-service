package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/**
 * Driving port: replay a closed round's buffered {@code CardPlayed}/{@code SpecialActionPlayed} actions in
 * strict priority-tier order ({@code NULLIFY -> SEAL -> ANNIHILATE -> CORRUPT
 * -> MIMIC -> AMPLIFY -> remaining cards by submission timestamp}). Player-targeted modifiers correlate from the
 * complete round, so their effect does not depend on relative submission order. Replaces
 * {@code ApplyProbabilityShiftUseCase}, {@code PlayCardModifierUseCase}, {@code PlaySpecialActionUseCase}, and
 * {@code ResolvePendingCorruptUseCase}
 * (design.md Decision 7, timeline-mvp9-resolution-ordering-paradox-cards) — those applied each action
 * immediately on consumption; this instead reads the whole round back once its {@code ActionRoundClosed}
 * arrives and applies it in one pass.
 */
public interface ReplayRoundActionsUseCase {

    void replay(UUID gameId, int eraNumber, int roundNumber);
}
