package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

/** The probe reads a lane's whole state, so its runner gives each test an empty lane. */
public interface ExecutionProbeContract extends TransactionalContract {

    Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    ExecutionProbe probe();

    ExecutionRepository probeExecutions();

    ProcessedEventPort probeProcessedEvents();

    ParadoxResolutionPhaseRepository probePhases();

    default void probeCreate(ParadoxResolutionPhase phase) {
        transactions().executeWithoutResult(status -> probePhases().createIfAbsent(phase));
    }

    @Test
    @DisplayName("probe: an empty lane has no game, has not ended and has no timers or publications")
    default void probe_emptyLane() {
        var observation = probe().observe(NOW);

        assertThat(observation.gameId()).isNull();
        assertThat(observation.gameEnded()).isFalse();
        assertThat(observation.dueTimers()).isZero();
        assertThat(observation.nextDeadline()).isNull();
        assertThat(probe().pendingPublications()).isZero();
    }

    @Test
    @DisplayName(
            "probe: the hosted game is the one bound to the open context, and it ends when its cleanup claim exists")
    default void probe_hostedGameAndItsEnd() {
        var game = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var execution = Execution.configure(ExecutionContextTestData.context("42"));
        probeExecutions().insertIfAbsent(execution);
        probeExecutions().bindGame(execution.context().caseKey(), game);

        assertThat(probe().observe(NOW).gameId()).isEqualTo(game);
        assertThat(probe().observe(NOW).gameEnded()).isFalse();

        probeProcessedEvents().claim(UUID.randomUUID(), "futureevent.game-ended");

        assertThat(probe().observe(NOW).gameEnded()).isTrue();
    }

    @Test
    @DisplayName("probe: waiting phases count as due by the clock and the next deadline is the earliest still ahead")
    default void probe_timersFollowTheLogicalClock() {
        var game = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var execution = Execution.configure(ExecutionContextTestData.context("42"));
        probeExecutions().insertIfAbsent(execution);
        probeExecutions().bindGame(execution.context().caseKey(), game);

        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(
                game, 1, ParadoxResolutionPhaseStatus.WAITING, NOW.minusSeconds(5)));
        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(game, 2, ParadoxResolutionPhaseStatus.WAITING, NOW));
        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(
                game, 3, ParadoxResolutionPhaseStatus.WAITING, NOW.plusSeconds(90)));
        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(
                game, 4, ParadoxResolutionPhaseStatus.WAITING, NOW.plusSeconds(30)));
        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(
                game, 5, ParadoxResolutionPhaseStatus.COMPLETED, NOW.minusSeconds(60)));
        probeCreate(ParadoxResolutionPhaseRepositoryContract.phase(
                UUID.randomUUID(), 1, ParadoxResolutionPhaseStatus.WAITING, NOW.minusSeconds(10)));

        var observation = probe().observe(NOW);

        assertThat(observation.dueTimers()).isEqualTo(2);
        assertThat(observation.nextDeadline()).isEqualTo(NOW.plusSeconds(30));
    }
}
