package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@ExtendWith(MockitoExtension.class)
class ParadoxResolutionPhaseOpenerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;

    @Mock
    ParadoxResolutionSagaImpl saga;

    @Mock
    ParadoxResolutionTimerScheduler timerScheduler;

    private ParadoxResolutionPhaseOpener opener;

    @BeforeEach
    void setUp() {
        opener = new ParadoxResolutionPhaseOpener(saga, timerScheduler);
    }

    @Test
    void open_phaseCreated_schedulesTimerAndReturnsItsPendingParadoxes() {
        var pending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        var timerExpiresAt = Instant.parse("2026-08-09T00:01:00Z");
        var phase = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                pending,
                List.of(),
                List.of(),
                List.of(),
                timerExpiresAt);
        given(saga.openPhase(GAME_ID, ERA_NUMBER, pending, List.of()))
                .willReturn(new ParadoxResolutionSagaImpl.OpenResult(phase, true));

        var result = opener.open(GAME_ID, ERA_NUMBER, pending, List.of());

        assertThat(result).isEqualTo(pending);
        then(timerScheduler).should().scheduleAfterCommit(phase.sagaId(), timerExpiresAt);
    }

    @Test
    void open_phaseAlreadyExisted_doesNotScheduleAndReturnsExistingPendingParadoxes() {
        // A duplicate/redelivered detection pass proposes fresh ids, but an existing phase's own ids are
        // what will ever receive a ParadoxCascaded — the caller must use those, and no second timer may
        // be scheduled for the same phase.
        var proposedPending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        var existingPending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        var existingPhase = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                existingPending,
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-08-09T00:01:00Z"));
        given(saga.openPhase(GAME_ID, ERA_NUMBER, proposedPending, List.of()))
                .willReturn(new ParadoxResolutionSagaImpl.OpenResult(existingPhase, false));

        var result = opener.open(GAME_ID, ERA_NUMBER, proposedPending, List.of());

        assertThat(result).isEqualTo(existingPending).isNotEqualTo(proposedPending);
        then(timerScheduler).should(never()).scheduleAfterCommit(any(), any());
    }
}
