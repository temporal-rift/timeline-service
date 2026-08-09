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
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository.CreateResult;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
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
    EraPlayersPort eraPlayers;

    @Mock
    TimelineEventPublisher publisher;

    @Mock
    ParadoxResolutionRulesPort rules;

    @Mock
    ProbabilityRulesPort probabilityRules;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

    private ParadoxResolutionSagaImpl saga;

    @BeforeEach
    void setUp() {
        saga = new ParadoxResolutionSagaImpl(
                stateManager, futureEvents, eraIndex, eraPlayers, publisher, rules, probabilityRules, clock);
    }

    @Test
    void openPhase_newEra_persistsAndPublishesPhaseStarted() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var pending = List.of(new PendingParadox(paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, affectedEventId, 0));
        var playerIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(playerIds);
        given(stateManager.createIfAbsent(any()))
                .willAnswer(invocation -> new CreateResult(invocation.getArgument(0), true));

        var result = saga.openPhase(GAME_ID, ERA_NUMBER, pending, List.of());

        assertThat(result.created()).isTrue();
        assertThat(result.phase().gameId()).isEqualTo(GAME_ID);
        assertThat(result.phase().eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(result.phase().pendingParadoxes()).isEqualTo(pending);
        assertThat(result.phase().pendingPlayerIds()).isEqualTo(playerIds);
        assertThat(result.phase().submissions()).isEmpty();
        assertThat(result.phase().timerExpiresAt()).isEqualTo(clock.instant().plusSeconds(TIMER_SECONDS));

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var payload = (ParadoxResolutionPhaseStarted) captor.getValue().payload();
        assertThat(payload.gameId()).isEqualTo(GAME_ID);
        assertThat(payload.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(payload.paradoxIds()).containsExactly(paradoxId);
        assertThat(payload.timerSeconds()).isEqualTo(TIMER_SECONDS);
    }

    @Test
    void openPhase_alreadyOpenForEra_returnsExistingPhaseAndPublishesNothing() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var existingPending =
                List.of(new PendingParadox(UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, UUID.randomUUID(), 0));
        var existingPhase = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                existingPending,
                List.of(),
                List.of(),
                List.of(),
                clock.instant().plusSeconds(999));
        given(stateManager.createIfAbsent(any())).willReturn(new CreateResult(existingPhase, false));

        var proposedPending =
                List.of(new PendingParadox(UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, UUID.randomUUID(), 0));
        var result = saga.openPhase(GAME_ID, ERA_NUMBER, proposedPending, List.of());

        assertThat(result.created()).isFalse();
        assertThat(result.phase()).isEqualTo(existingPhase);
        then(publisher).should(never()).publish(any());
    }

    @Test
    void handleTimerExpiry_singlePendingParadoxUntouched_stillCascadesAndCarriesEventForward() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId);
        var resolvedTerminalResolutions = List.of(new TerminalResolution(
                UUID.randomUUID(), 1, TerminalResolution.TerminalState.OUTCOME_APPLIED, UUID.randomUUID()));
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, affectedEventId, 0)),
                resolvedTerminalResolutions,
                List.of(),
                List.of(),
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
    void handleTimerExpiry_multiplePendingParadoxesUntouched_cascadesEachOne() {
        var sagaId = UUID.randomUUID();
        var eventId0 = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(
                        new PendingParadox(UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, eventId0, 0),
                        new PendingParadox(UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, eventId1, 1)),
                List.of(),
                List.of(),
                List.of(),
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(eventId0)).willReturn(impossibleErasureFutureEvent(eventId0, UUID.randomUUID()));
        given(futureEvents.findById(eventId1)).willReturn(impossibleErasureFutureEvent(eventId1, UUID.randomUUID()));

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
                List.of(new PendingParadox(UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, UUID.randomUUID(), 0)),
                List.of(),
                List.of(),
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

    @Test
    void handlePlayerSubmitted_notAllSubmittedYet_doesNotClose() {
        var submission = new Submission(UUID.randomUUID(), "PUSH", UUID.randomUUID(), UUID.randomUUID());
        var stillPendingPhase = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(),
                List.of(),
                List.of(UUID.randomUUID()),
                List.of(submission),
                clock.instant());
        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(stillPendingPhase));

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(publisher).should(never()).publish(any());
        then(stateManager).should(never()).complete(any());
    }

    @Test
    void handlePlayerSubmitted_phaseNotAcceptingSubmissions_doesNothing() {
        var submission = new Submission(UUID.randomUUID(), "PUSH", UUID.randomUUID(), UUID.randomUUID());
        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.empty());

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(publisher).should(never()).publish(any());
        then(stateManager).should(never()).complete(any());
    }

    @Test
    void handlePlayerSubmitted_allSubmitted_appliedCardClearsParadox_publishesResolvedThenOutcomeApplied() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId);
        var submission = new Submission(playerId, "SUPPRESS", affectedEventId, annihilatedId);
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.suppressShift()).willReturn(-40);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(stateManager).should().complete(phase);
        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        var resolved = (ParadoxResolved) payloads.get(0);
        assertThat(resolved.paradoxId()).isEqualTo(paradoxId);
        assertThat(resolved.resolvedByPlayerId()).isEqualTo(playerId);

        assertThat(payloads.get(1)).isInstanceOf(OutcomeApplied.class);

        var barrier = (EraResolutionCompleted) payloads.get(2);
        assertThat(barrier.terminalResolutions())
                .extracting(TerminalResolution::terminalState)
                .containsExactly(TerminalResolution.TerminalState.OUTCOME_APPLIED);
    }

    @Test
    void handlePlayerSubmitted_allSubmitted_appliedCardDoesNotClearParadox_stillCascades() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var futureEvent =
                impossibleErasureFutureEvent(affectedEventId, annihilatedId, secondOutcomeId, UUID.randomUUID());
        // A small PUSH to the non-annihilated "second" outcome — the annihilated outcome stays >= both others.
        var submission = new Submission(playerId, "PUSH", affectedEventId, secondOutcomeId);
        var phase = new ParadoxResolutionPhase(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.pushShift()).willReturn(1);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(payloads.get(0)).isInstanceOf(ParadoxCascaded.class);
        assertThat(payloads.get(1)).isInstanceOf(EraResolutionCompleted.class);
    }

    /** Three outcomes, the first annihilated and at least tied with the other two — triggers IMPOSSIBLE_ERASURE. */
    private static FutureEvent impossibleErasureFutureEvent(UUID eventId, UUID annihilatedOutcomeId) {
        return impossibleErasureFutureEvent(eventId, annihilatedOutcomeId, UUID.randomUUID(), UUID.randomUUID());
    }

    private static FutureEvent impossibleErasureFutureEvent(
            UUID eventId, UUID annihilatedOutcomeId, UUID secondOutcomeId, UUID thirdOutcomeId) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(annihilatedOutcomeId, "annihilated", 50, false, true),
                                new Outcome(secondOutcomeId, "second", 30),
                                new Outcome(thirdOutcomeId, "third", 20)))));
    }
}
