package io.github.temporalrift.timeline.application.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository.CreateResult;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;

@Component
class ParadoxResolutionPhaseStateManager {

    private final ParadoxResolutionPhaseRepository repository;

    ParadoxResolutionPhaseStateManager(ParadoxResolutionPhaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    CreateResult createIfAbsent(ParadoxResolutionPhase candidate) {
        return repository.createIfAbsent(candidate);
    }

    @Transactional
    Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return repository.findBySagaIdWithLock(sagaId);
    }

    @Transactional
    void complete(ParadoxResolutionPhase phase) {
        repository.save(phase.complete());
    }

    List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline) {
        return repository.findWaitingDueBy(deadline);
    }
}
