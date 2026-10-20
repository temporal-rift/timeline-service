package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Component
@Profile("in-memory")
class InMemoryExecutionProbe implements ExecutionProbe {

    // The claim the lifecycle consumer records once its GameEnded cleanup has committed.
    private static final String GAME_ENDED_CONSUMER = "futureevent.game-ended";

    private final Table<ProcessedClaim, Boolean> claims;
    private final Table<Object, ParadoxResolutionPhase> phases;
    private final InMemoryOutboxStore outbox;
    private final ExecutionRepository executions;

    InMemoryExecutionProbe(InMemoryDatabase database, InMemoryOutboxStore outbox, ExecutionRepository executions) {
        this.claims = database.table(TableNames.PROCESSED_EVENTS);
        this.phases = database.table(TableNames.PARADOX_RESOLUTION_PHASE);
        this.outbox = outbox;
        this.executions = executions;
    }

    @Override
    public Observation observe(Instant now) {
        var gameId = executions.find().map(Execution::gameId).orElse(null);
        var gameEnded = claims.entries(claimed -> true).stream()
                .map(Map.Entry::getKey)
                .anyMatch(claim -> claim.consumer().equals(GAME_ENDED_CONSUMER));
        if (gameId == null) {
            return new Observation(null, false, 0, null);
        }
        var dueTimers = phases.list(phase -> phase.gameId().equals(gameId)
                        && InMemoryParadoxResolutionPhaseAdapter.isWaitingDueBy(phase, now))
                .size();
        var nextDeadline = phases
                .list(phase -> phase.gameId().equals(gameId)
                        && phase.status() == ParadoxResolutionPhaseStatus.WAITING
                        && phase.timerExpiresAt() != null
                        && phase.timerExpiresAt().isAfter(now))
                .stream()
                .map(ParadoxResolutionPhase::timerExpiresAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        return new Observation(gameId, gameEnded, dueTimers, nextDeadline);
    }

    @Override
    public int pendingPublications() {
        return outbox.unsent();
    }
}
