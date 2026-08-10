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
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
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
    public CreateResult createIfAbsent(ParadoxResolutionPhase candidate) {
        var insertedRows = jpaRepository.insertIfAbsent(
                candidate.sagaId(),
                candidate.gameId(),
                candidate.eraNumber(),
                candidate.status().name(),
                objectMapper.writeValueAsString(candidate.pendingParadoxes()),
                objectMapper.writeValueAsString(candidate.resolvedTerminalResolutions()),
                pendingPlayerIdsColumn(candidate),
                objectMapper.writeValueAsString(candidate.submissions()),
                candidate.timerExpiresAt());
        if (insertedRows > 0) {
            return new CreateResult(candidate, true);
        }
        var existing = jpaRepository
                .findByGameIdAndEraNumber(candidate.gameId(), candidate.eraNumber())
                .map(this::toDomain)
                .orElseThrow(() -> new IllegalStateException("paradox_resolution_phase insert for game "
                        + candidate.gameId() + " era " + candidate.eraNumber()
                        + " conflicted but no existing row was found"));
        return new CreateResult(existing, false);
    }

    @Override
    public ParadoxResolutionPhase save(ParadoxResolutionPhase phase) {
        jpaRepository.save(toEntity(phase));
        return phase;
    }

    @Override
    public Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return jpaRepository.findBySagaIdWithLock(sagaId).map(this::toDomain);
    }

    @Override
    public Optional<ParadoxResolutionPhase> findByGameIdAndEraNumberWithLock(UUID gameId, int eraNumber) {
        return jpaRepository.findByGameIdAndEraNumberWithLock(gameId, eraNumber).map(this::toDomain);
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
        entity.setPendingPlayerIds(pendingPlayerIdsColumn(phase));
        entity.setSubmissions(objectMapper.writeValueAsString(phase.submissions()));
        entity.setTimerExpiresAt(phase.timerExpiresAt());
        return entity;
    }

    /** {@code null} — not {@code "[]"} — for a phase whose era roster is not known yet (design.md Decision 1). */
    private String pendingPlayerIdsColumn(ParadoxResolutionPhase phase) {
        return phase.rosterKnown() ? objectMapper.writeValueAsString(phase.pendingPlayerIds()) : null;
    }

    private ParadoxResolutionPhase toDomain(ParadoxResolutionPhaseEntity entity) {
        var status = ParadoxResolutionPhaseStatus.valueOf(entity.getStatus());
        var pendingParadoxes = List.of(objectMapper.readValue(entity.getPendingParadoxes(), PendingParadox[].class));
        var resolvedTerminalResolutions =
                List.of(objectMapper.readValue(entity.getResolvedTerminalResolutions(), TerminalResolution[].class));
        var submissions = List.of(objectMapper.readValue(entity.getSubmissions(), Submission[].class));
        if (entity.getPendingPlayerIds() == null) {
            return ParadoxResolutionPhase.withUnknownRoster(
                    entity.getSagaId(),
                    entity.getGameId(),
                    entity.getEraNumber(),
                    status,
                    pendingParadoxes,
                    resolvedTerminalResolutions,
                    submissions,
                    entity.getTimerExpiresAt());
        }
        return ParadoxResolutionPhase.withKnownRoster(
                entity.getSagaId(),
                entity.getGameId(),
                entity.getEraNumber(),
                status,
                pendingParadoxes,
                resolvedTerminalResolutions,
                List.of(objectMapper.readValue(entity.getPendingPlayerIds(), UUID[].class)),
                submissions,
                entity.getTimerExpiresAt());
    }
}
