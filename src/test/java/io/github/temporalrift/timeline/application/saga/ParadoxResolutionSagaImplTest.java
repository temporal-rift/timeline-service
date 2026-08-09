package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@ExtendWith(MockitoExtension.class)
class ParadoxResolutionSagaImplTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final int TIMER_SECONDS = 60;

    @Mock
    ParadoxResolutionPhaseStateManager stateManager;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    TimelineEventPublisher publisher;

    @Mock
    ParadoxResolutionRulesPort rules;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

    private ParadoxResolutionSagaImpl saga;

    @BeforeEach
    void setUp() {
        saga = new ParadoxResolutionSagaImpl(stateManager, futureEvents, eraIndex, publisher, rules, clock);
    }

    @Test
    void openPhase_newEra_persistsAndPublishesPhaseStarted() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var pending = List.of(new PendingParadox(paradoxId, affectedEventId, 0));
        var sagaId = UUID.randomUUID();
        var timerExpiresAt = clock.instant().plusSeconds(TIMER_SECONDS);
        var persisted = new ParadoxResolutionPhase(
                sagaId, GAME_ID, ERA_NUMBER, ParadoxResolutionPhaseStatus.WAITING, pending, List.of(), timerExpiresAt);
        given(stateManager.open(GAME_ID, ERA_NUMBER, pending, List.of(), timerExpiresAt))
                .willReturn(Optional.of(persisted));

        var result = saga.openPhase(GAME_ID, ERA_NUMBER, pending, List.of());

        assertThat(result).isPresent();
        assertThat(result.get().sagaId()).isEqualTo(sagaId);
        assertThat(result.get().timerExpiresAt()).isEqualTo(timerExpiresAt);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var payload = (ParadoxResolutionPhaseStarted) captor.getValue().payload();
        assertThat(payload.gameId()).isEqualTo(GAME_ID);
        assertThat(payload.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(payload.paradoxIds()).containsExactly(paradoxId);
        assertThat(payload.timerSeconds()).isEqualTo(TIMER_SECONDS);
    }

    @Test
    void openPhase_alreadyOpenForEra_returnsEmptyAndPublishesNothing() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        given(stateManager.open(any(), anyInt(), any(), any(), any())).willReturn(Optional.empty());

        var result = saga.openPhase(
                GAME_ID, ERA_NUMBER, List.of(new PendingParadox(UUID.randomUUID(), UUID.randomUUID(), 0)), List.of());

        assertThat(result).isEmpty();
        then(publisher).should(never()).publish(any());
    }

    @Test
    void handleTimerExpiry_singlePendingParadox_publishesCascadedThenBarrierAndCarriesEventForward() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(affectedEventId, outcomeId);
        var resolvedTerminalResolutions = List.of(new TerminalResolution(
                UUID.randomUUID(), 1, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId));
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(paradoxId, affectedEventId, 0)),
                resolvedTerminalResolutions,
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handleTimerExpiry(sagaId);

        then(stateManager).should().complete(phase);
        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        var cascaded = (ParadoxCascaded) payloads.get(0);
        assertThat(cascaded.paradoxId()).isEqualTo(paradoxId);
        assertThat(cascaded.affectedEventId()).isEqualTo(affectedEventId);
        assertThat(cascaded.carryForwardProbabilityState()).isEqualTo(futureEvent.outcomes());

        var barrier = (EraResolutionCompleted) payloads.get(1);
        assertThat(barrier.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(affectedEventId, 0, TerminalResolution.TerminalState.CASCADED, null),
                        resolvedTerminalResolutions.getFirst());
    }

    @Test
    void handleTimerExpiry_multiplePendingParadoxes_cascadesEachOne() {
        var sagaId = UUID.randomUUID();
        var eventId0 = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(
                        new PendingParadox(UUID.randomUUID(), eventId0, 0),
                        new PendingParadox(UUID.randomUUID(), eventId1, 1)),
                List.of(),
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(eventId0)).willReturn(draftedFutureEvent(eventId0, UUID.randomUUID()));
        given(futureEvents.findById(eventId1)).willReturn(draftedFutureEvent(eventId1, UUID.randomUUID()));

        saga.handleTimerExpiry(sagaId);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(payloads.subList(0, 2)).allSatisfy(p -> assertThat(p).isInstanceOf(ParadoxCascaded.class));
        var barrier = (EraResolutionCompleted) payloads.get(2);
        assertThat(barrier.terminalResolutions())
                .extracting(TerminalResolution::eventId)
                .containsExactly(eventId0, eventId1);
    }

    @Test
    void handleTimerExpiry_phaseAlreadyCompleted_doesNothing() {
        var sagaId = UUID.randomUUID();
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.COMPLETED,
                List.of(new PendingParadox(UUID.randomUUID(), UUID.randomUUID(), 0)),
                List.of(),
                clock.instant());
        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));

        saga.handleTimerExpiry(sagaId);

        then(publisher).should(never()).publish(any());
        then(stateManager).should(never()).complete(any());
        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
    }

    @Test
    void handleTimerExpiry_phaseNotFound_doesNothing() {
        var sagaId = UUID.randomUUID();
        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.empty());

        saga.handleTimerExpiry(sagaId);

        then(publisher).should(never()).publish(any());
    }

    private static FutureEvent draftedFutureEvent(UUID eventId, UUID outcomeId) {
        return FutureEvent.replay(
                eventId, List.of(new FutureEventDrafted(eventId, List.of(new Outcome(outcomeId, "d", 100)))));
    }
}
