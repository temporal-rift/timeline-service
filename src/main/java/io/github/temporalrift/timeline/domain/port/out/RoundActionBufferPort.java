package io.github.temporalrift.timeline.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Driven port for the round-scoped action buffer (design.md Decision 1): every {@code CardPlayed}/
 * {@code SpecialActionPlayed} consumed during a round is recorded here without applying its effect. At
 * {@code ActionRoundClosed}, the whole round is read back once and replayed in priority-tier order in a single
 * in-process pass — the buffer is never updated after being written, and every "last card"/"pending" concept the
 * old per-message-dispatch model needed a durable port for (design.md Decision 7) is now a value computed once
 * over this list, local to that one replay call.
 */
public interface RoundActionBufferPort {

    void save(UUID gameId, int eraNumber, int roundNumber, BufferedAction action);

    /** Returned in no particular order; callers sort by {@link BufferedAction#occurredAt()} as needed. */
    List<BufferedAction> findByRound(UUID gameId, int eraNumber, int roundNumber);

    enum ActionKind {
        CARD_PLAYED,
        SPECIAL_ACTION_PLAYED
    }

    /**
     * A buffered, not-yet-applied round action. {@code cardType} is populated for {@code CARD_PLAYED} only;
     * {@code specialAction} for {@code SPECIAL_ACTION_PLAYED} only. {@code cardInstanceId}/{@code sourceOutcomeId}/
     * {@code targetPlayerId} are populated only when the originating payload carries them (see
     * {@code CardPlayedPayload}/{@code SpecialActionPlayedPayload}). {@code envelopeEventId} is the tie-break
     * secondary sort key for two actions sharing an identical {@code occurredAt} (design.md Decision 5).
     */
    record BufferedAction(
            ActionKind kind,
            String cardType,
            String specialAction,
            UUID playerId,
            UUID cardInstanceId,
            UUID targetEventId,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            UUID targetPlayerId,
            Instant occurredAt,
            UUID envelopeEventId) {}
}
