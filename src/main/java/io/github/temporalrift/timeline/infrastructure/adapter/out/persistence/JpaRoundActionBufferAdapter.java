package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;

@Repository
class JpaRoundActionBufferAdapter implements RoundActionBufferPort {

    private final RoundActionBufferJpaRepository repository;
    private final ObjectMapper objectMapper;

    JpaRoundActionBufferAdapter(RoundActionBufferJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, BufferedAction action) {
        repository.save(new RoundActionBufferEntity(
                new RoundKey(gameId, eraNumber, roundNumber),
                action.kind(),
                action.cardType(),
                action.specialAction(),
                action.grade(),
                action.playerId(),
                action.cardInstanceId(),
                action.targetEventId(),
                action.targetEventIds() == null ? null : objectMapper.writeValueAsString(action.targetEventIds()),
                action.sourceOutcomeId(),
                action.targetOutcomeId(),
                action.targetPlayerId(),
                action.occurredAt(),
                action.envelopeEventId()));
    }

    @Override
    public List<BufferedAction> findByRound(UUID gameId, int eraNumber, int roundNumber) {
        return repository.findByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber).stream()
                .map(this::toBufferedAction)
                .toList();
    }

    private BufferedAction toBufferedAction(RoundActionBufferEntity e) {
        return new BufferedAction(
                e.kind(),
                e.cardType(),
                e.specialAction(),
                e.playerId(),
                e.cardInstanceId(),
                e.targetEventId(),
                toTargetEventIds(e.targetEventIds()),
                e.sourceOutcomeId(),
                e.targetOutcomeId(),
                e.targetPlayerId(),
                e.grade(),
                e.occurredAt(),
                e.envelopeEventId());
    }

    /** Drops any null entry a degenerate persisted array might contain — {@code List.of} rejects nulls outright. */
    private List<UUID> toTargetEventIds(String json) {
        if (json == null) {
            return null;
        }
        return Arrays.stream(objectMapper.readValue(json, UUID[].class))
                .filter(Objects::nonNull)
                .toList();
    }
}
