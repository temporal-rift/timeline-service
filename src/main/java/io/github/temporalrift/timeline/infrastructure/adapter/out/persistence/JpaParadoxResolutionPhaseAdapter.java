package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Repository
class JpaParadoxResolutionPhaseAdapter implements ParadoxResolutionPhaseRepository {

    private static final int SWEEP_BATCH_SIZE = 200;

    private final ParadoxResolutionPhaseJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    JpaParadoxResolutionPhaseAdapter(ParadoxResolutionPhaseJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ParadoxResolutionPhase save(ParadoxResolutionPhase phase) {
        jpaRepository.save(toEntity(phase));
        return phase;
    }

    @Override
    public Optional<ParadoxResolutionPhase> findByGameIdAndEraNumber(UUID gameId, int eraNumber) {
        return jpaRepository.findByGameIdAndEraNumber(gameId, eraNumber).map(this::toDomain);
    }

    @Override
    public Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return jpaRepository.findBySagaIdWithLock(sagaId).map(this::toDomain);
    }

    @Override
    public List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline) {
        return jpaRepository.findWaitingDueBy(deadline, PageRequest.ofSize(SWEEP_BATCH_SIZE)).stream()
                .map(this::toDomain)
                .toList();
    }

    private ParadoxResolutionPhaseEntity toEntity(ParadoxResolutionPhase phase) {
        var entity = new ParadoxResolutionPhaseEntity();
        entity.setSagaId(phase.sagaId());
        entity.setGameId(phase.gameId());
        entity.setEraNumber(phase.eraNumber());
        entity.setStatus(phase.status().name());
        entity.setPendingParadoxes(objectMapper.writeValueAsString(phase.pendingParadoxes()));
        entity.setResolvedTerminalResolutions(objectMapper.writeValueAsString(phase.resolvedTerminalResolutions()));
        entity.setTimerExpiresAt(phase.timerExpiresAt());
        return entity;
    }

    private ParadoxResolutionPhase toDomain(ParadoxResolutionPhaseEntity entity) {
        return new ParadoxResolutionPhase(
                entity.getSagaId(),
                entity.getGameId(),
                entity.getEraNumber(),
                ParadoxResolutionPhaseStatus.valueOf(entity.getStatus()),
                List.of(objectMapper.readValue(entity.getPendingParadoxes(), PendingParadox[].class)),
                List.of(objectMapper.readValue(entity.getResolvedTerminalResolutions(), TerminalResolution[].class)),
                entity.getTimerExpiresAt());
    }
}
