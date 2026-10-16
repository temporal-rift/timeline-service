package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Component
@Profile("in-memory")
class InMemoryExecutionProbe implements ExecutionProbe {

    // The claim the lifecycle consumer records once its GameEnded cleanup has committed.
    private static final String GAME_ENDED_CONSUMER = "futureevent.game-ended";

    private final Table<MembershipKey, GameMembership> memberships;
    private final Table<ProcessedClaim, Boolean> claims;
    private final Table<Object, ParadoxResolutionPhase> phases;
    private final InMemoryOutboxStore outbox;

    InMemoryExecutionProbe(InMemoryDatabase database, InMemoryOutboxStore outbox) {
        this.memberships = database.table(TableNames.GAME_MEMBERSHIP);
        this.claims = database.table(TableNames.PROCESSED_EVENTS);
        this.phases = database.table(TableNames.PARADOX_RESOLUTION_PHASE);
        this.outbox = outbox;
    }

    @Override
    public Observation observe(Instant now) {
        var gameId = memberships.list().stream()
                .map(GameMembership::gameId)
                .min(InMemoryExecutionProbe::databaseOrder)
                .orElse(null);
        var gameEnded = claims.entries(claimed -> true).stream()
                .map(Map.Entry::getKey)
                .anyMatch(claim -> claim.consumer().equals(GAME_ENDED_CONSUMER));
        var dueTimers = phases.list(phase -> InMemoryParadoxResolutionPhaseAdapter.isWaitingDueBy(phase, now))
                .size();
        var nextDeadline = phases
                .list(phase -> phase.status() == ParadoxResolutionPhaseStatus.WAITING
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

    // PostgreSQL orders uuid values as unsigned bytes, which is not the signed order of UUID#compareTo.
    private static int databaseOrder(UUID left, UUID right) {
        var high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
