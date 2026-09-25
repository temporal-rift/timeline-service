package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.OutcomesCollided;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
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
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

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

    @Mock
    io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository chainSagas;

    @Mock
    io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository chains;

    @Mock
    io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase weaverChainSaga;

    @Mock
    io.github.temporalrift.timeline.application.port.in.SettleCascadeCarryForwardUseCase settleCascades;

    @Mock
    RandomGenerator random;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

    private ParadoxResolutionSagaImpl saga;

    @BeforeEach
    void setUp() {
        saga = new ParadoxResolutionSagaImpl(
                stateManager,
                futureEvents,
                eraIndex,
                eraPlayers,
                publisher,
                rules,
                probabilityRules,
                chainSagas,
                chains,
                weaverChainSaga,
                settleCascades,
                clock,
                random);
        lenient().when(chainSagas.findOpenByGame(any())).thenReturn(List.of());
    }

    @Test
    void openPhase_newEra_persistsAndPublishesPhaseStarted() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var pending = List.of(new PendingParadox(
                paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), affectedEventId, 0));
        var playerIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(Optional.of(playerIds));
        given(stateManager.createIfAbsent(any()))
                .willAnswer(invocation -> new CreateResult(invocation.getArgument(0), true));

        var result = saga.openPhase(GAME_ID, ERA_NUMBER, pending, List.of());

        assertThat(result.created()).isTrue();
        assertThat(result.phase().gameId()).isEqualTo(GAME_ID);
        assertThat(result.phase().eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(result.phase().pendingParadoxes()).isEqualTo(pending);
        assertThat(result.phase().rosterKnown()).isTrue();
        assertThat(result.phase().pendingPlayerIds()).isEqualTo(playerIds);
        assertThat(result.phase().submissions()).isEmpty();
        assertThat(result.phase().timerExpiresAt()).isEqualTo(clock.instant().plusSeconds(TIMER_SECONDS));

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var payload = (ParadoxResolutionPhaseStarted) captor.getValue().payload();
        assertThat(payload.gameId()).isEqualTo(GAME_ID);
        assertThat(payload.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(payload.paradoxIds()).containsExactly(paradoxId);
        assertThat(payload.affectedEventIds()).containsExactly(affectedEventId);
        assertThat(payload.timerSeconds()).isEqualTo(TIMER_SECONDS);
    }

    @Test
    void openPhase_alreadyOpenForEra_returnsExistingPhaseAndPublishesNothing() {
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var existingPending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        var existingPhase = ParadoxResolutionPhase.withKnownRoster(
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

        var proposedPending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        var result = saga.openPhase(GAME_ID, ERA_NUMBER, proposedPending, List.of());

        assertThat(result.created()).isFalse();
        assertThat(result.phase()).isEqualTo(existingPhase);
        then(publisher).should(never()).publish(any());
    }

    @Test
    void openPhase_rosterNotPersistedYet_opensWithAnUnknownRosterRatherThanAnEmptyOne() {
        // EraStartedKafkaConsumer runs in its own consumer group — nothing orders it against the group that
        // opens phases, so an absent roster must not read as "nobody left to submit" (issue #41).
        given(rules.paradoxResolutionTimerSeconds()).willReturn(TIMER_SECONDS);
        var pending = List.of(new PendingParadox(
                UUID.randomUUID(), ParadoxType.IMPOSSIBLE_ERASURE, List.of(UUID.randomUUID()), UUID.randomUUID(), 0));
        given(eraPlayers.find(GAME_ID, ERA_NUMBER)).willReturn(Optional.empty());
        given(stateManager.createIfAbsent(any()))
                .willAnswer(invocation -> new CreateResult(invocation.getArgument(0), true));

        var result = saga.openPhase(GAME_ID, ERA_NUMBER, pending, List.of());

        assertThat(result.phase().rosterKnown()).isFalse();
        assertThat(result.phase().pendingPlayerIds()).isEmpty();
        assertThat(result.phase().allPlayersSubmitted()).isFalse();
    }

    @Test
    void handlePlayerSubmitted_phaseRosterStillUnknown_doesNotClose() {
        var submission = new Submission(UUID.randomUUID(), "PUSH", CardGrade.II, UUID.randomUUID(), UUID.randomUUID());
        var unknownRosterPhase = ParadoxResolutionPhase.withUnknownRoster(
                UUID.randomUUID(),
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());
        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(unknownRosterPhase));

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(publisher).should(never()).publish(any());
        then(stateManager).should(never()).complete(any());
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
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
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
        assertThat(cascaded.paradoxIds()).containsExactly(paradoxId);
        assertThat(cascaded.affectedEventId()).isEqualTo(affectedEventId);
        assertThat(cascaded.carryForwardProbabilityState()).isEqualTo(futureEvent.outcomes());

        var barrier = (EraResolutionCompleted) payloads.get(1);
        assertThat(barrier.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(affectedEventId, 0, TerminalResolution.TerminalState.CASCADED, null),
                        resolvedTerminalResolutions.getFirst());
        then(settleCascades).should().settle(GAME_ID, ERA_NUMBER, barrier.terminalResolutions());
    }

    @Test
    void handleTimerExpiry_chainConflictPersists_cascadesInsteadOfResolvingAndBreaksChain() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var futureEvent = cleanFutureEvent(affectedEventId, pendingOutcomeId, UUID.randomUUID());
        futureEvent.annihilateOutcome(pendingOutcomeId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.CHAIN_CONFLICT, List.of(pendingOutcomeId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(),
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        givenActiveChainWithPendingLink(affectedEventId, pendingOutcomeId);

        saga.handleTimerExpiry(sagaId);

        then(stateManager).should().complete(phase);
        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);
        then(weaverChainSaga)
                .should()
                .breakChainOnCascadedParadox(GAME_ID, ERA_NUMBER, affectedEventId, pendingOutcomeId, paradoxId);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        var cascaded = (ParadoxCascaded) payloads.get(0);
        assertThat(cascaded.paradoxId()).isEqualTo(paradoxId);
        assertThat(cascaded.paradoxIds()).containsExactly(paradoxId);
        assertThat(cascaded.affectedEventId()).isEqualTo(affectedEventId);

        var barrier = (EraResolutionCompleted) payloads.get(1);
        assertThat(barrier.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(affectedEventId, 0, TerminalResolution.TerminalState.CASCADED, null));
        assertThat(payloads).noneMatch(OutcomeApplied.class::isInstance);
    }

    @Test
    void handleTimerExpiry_chainConflictStabilized_resolvesAndConfirmsPendingLink() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var futureEvent = cleanFutureEvent(affectedEventId, pendingOutcomeId, UUID.randomUUID());
        futureEvent.annihilateOutcome(pendingOutcomeId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.CHAIN_CONFLICT, List.of(pendingOutcomeId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(new Submission(UUID.randomUUID(), "STABILIZE", null, affectedEventId, null)),
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        givenActiveChainWithPendingLink(affectedEventId, pendingOutcomeId);

        saga.handleTimerExpiry(sagaId);

        then(weaverChainSaga)
                .should()
                .confirmParadoxResolvedLink(GAME_ID, ERA_NUMBER, affectedEventId, pendingOutcomeId);
        then(weaverChainSaga).should(never()).breakChainOnCascadedParadox(any(), anyInt(), any(), any(), any());

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(payloads)
                .anyMatch(ParadoxResolved.class::isInstance)
                .anyMatch(OutcomeApplied.class::isInstance)
                .anyMatch(EraResolutionCompleted.class::isInstance);
        then(weaverChainSaga).should().resolvePendingLink(eq(GAME_ID), eq(ERA_NUMBER), eq(affectedEventId), any());
    }

    @Test
    void handleTimerExpiry_multiplePendingParadoxesUntouched_cascadesEachOne() {
        var sagaId = UUID.randomUUID();
        var eventId0 = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var annihilatedId0 = UUID.randomUUID();
        var annihilatedId1 = UUID.randomUUID();
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(
                        new PendingParadox(
                                UUID.randomUUID(),
                                ParadoxType.IMPOSSIBLE_ERASURE,
                                List.of(annihilatedId0),
                                eventId0,
                                0),
                        new PendingParadox(
                                UUID.randomUUID(),
                                ParadoxType.IMPOSSIBLE_ERASURE,
                                List.of(annihilatedId1),
                                eventId1,
                                1)),
                List.of(),
                List.of(),
                List.of(),
                clock.instant());

        given(stateManager.findBySagaIdWithLock(sagaId)).willReturn(Optional.of(phase));
        given(futureEvents.findById(eventId0)).willReturn(impossibleErasureFutureEvent(eventId0, annihilatedId0));
        given(futureEvents.findById(eventId1)).willReturn(impossibleErasureFutureEvent(eventId1, annihilatedId1));

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
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.COMPLETED,
                List.of(new PendingParadox(
                        UUID.randomUUID(),
                        ParadoxType.IMPOSSIBLE_ERASURE,
                        List.of(UUID.randomUUID()),
                        UUID.randomUUID(),
                        0)),
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
        var submission = new Submission(UUID.randomUUID(), "PUSH", CardGrade.II, UUID.randomUUID(), UUID.randomUUID());
        var stillPendingPhase = ParadoxResolutionPhase.withKnownRoster(
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
        var submission = new Submission(UUID.randomUUID(), "PUSH", CardGrade.II, UUID.randomUUID(), UUID.randomUUID());
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
        var submission = new Submission(playerId, "SUPPRESS", CardGrade.II, affectedEventId, annihilatedId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.suppressShift(CardGrade.II)).willReturn(-40);
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
        then(settleCascades).should().settle(GAME_ID, ERA_NUMBER, barrier.terminalResolutions());
    }

    @Test
    void handlePlayerSubmitted_gradeSpecificPushMagnitude_appliesConfiguredValueForThatGrade() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId, secondOutcomeId, thirdOutcomeId);
        var submission = new Submission(playerId, "PUSH", CardGrade.III, affectedEventId, secondOutcomeId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.pushShift(CardGrade.III)).willReturn(30);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        // The target outcome's own probability deterministically reflects the grade III-configured +30, not
        // the grade II baseline's +20 — independent of how the corresponding decrease redistributes.
        assertThat(futureEvent.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(secondOutcomeId))
                        .findFirst()
                        .orElseThrow()
                        .probability())
                .isEqualTo(30);
    }

    @Test
    void handlePlayerSubmitted_allSubmitted_appliedCardDoesNotClearParadox_stillCascades() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var futureEvent = twoErasuresFutureEvent(affectedEventId, annihilatedId, secondOutcomeId, UUID.randomUUID());
        // Pushing the other annihilated outcome takes its point from the first one; the eligible outcome stays at 0.
        var submission = new Submission(playerId, "PUSH", CardGrade.II, affectedEventId, secondOutcomeId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.IMPOSSIBLE_ERASURE,
                        List.of(annihilatedId, secondOutcomeId),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.pushShift(CardGrade.II)).willReturn(1);
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

    @Test
    void handlePlayerSubmitted_pushAgainstSealedOutcomeIsBlockedWithoutBreach_eventResolves() {
        // A PUSH naming a sealed outcome is declined with weights unchanged and records no breach, so it
        // cannot keep the event from resolving once the tracked IMPOSSIBLE_ERASURE clears — the suppressed
        // annihilated outcome's freed amount goes entirely to "third" since the sealed outcome absorbs none.
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var sealedOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var pushingPlayerId = UUID.randomUUID();
        var suppressingPlayerId = UUID.randomUUID();
        var futureEvent = FutureEvent.replay(
                affectedEventId,
                List.of(new FutureEventDrafted(
                        affectedEventId,
                        List.of(
                                new Outcome(annihilatedId, "annihilated", 50, false, true),
                                new Outcome(sealedOutcomeId, "sealed", 30, true, false),
                                new Outcome(thirdOutcomeId, "third", 20)))));
        // Blocked by the seal (no probability change) — then clears the erasure by suppressing the annihilated
        // outcome; the sealed outcome is untouched by the redistribution since it's sealed, so the freed amount
        // goes entirely to "third".
        var blockedSubmission = new Submission(pushingPlayerId, "PUSH", CardGrade.II, affectedEventId, sealedOutcomeId);
        var suppressSubmission =
                new Submission(suppressingPlayerId, "SUPPRESS", CardGrade.II, affectedEventId, annihilatedId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(blockedSubmission, suppressSubmission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, suppressSubmission))
                .willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.pushShift(CardGrade.II)).willReturn(20);
        given(probabilityRules.suppressShift(CardGrade.II)).willReturn(-30);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, suppressSubmission);

        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
        then(stateManager).should().complete(phase);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        // The original IMPOSSIBLE_ERASURE finding cleared...
        var resolved = (ParadoxResolved) payloads.get(0);
        assertThat(resolved.paradoxId()).isEqualTo(paradoxId);
        // ...and with no SEAL_BREACH in its place, the event resolves normally (the weighted draw's winner
        // is random, so only the terminal state and event are asserted, never the winning outcome).
        assertThat(payloads.get(1)).isInstanceOf(OutcomeApplied.class);
        var barrier = (EraResolutionCompleted) payloads.get(2);
        assertThat(barrier.terminalResolutions()).singleElement().satisfies(terminal -> {
            assertThat(terminal.eventId()).isEqualTo(affectedEventId);
            assertThat(terminal.terminalState()).isEqualTo(TerminalResolution.TerminalState.OUTCOME_APPLIED);
        });
    }

    @Test
    void handlePlayerSubmitted_clearsOriginalFindingButCreatesDeadHeat_announcesAndCascades() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 100, false, true),
                new Outcome(secondOutcomeId, "second", 0),
                new Outcome(thirdOutcomeId, "third", 0));
        var futureEvent = FutureEvent.replay(
                affectedEventId,
                List.of(
                        new FutureEventDrafted(affectedEventId, outcomes),
                        new OutcomesCollided(affectedEventId, outcomes, secondOutcomeId, thirdOutcomeId)));
        // Suppressing the annihilated outcome frees 20 points split evenly across the collided pair: the erasure
        // clears, and the pair now leads tied at 10 — a new dead heat.
        var submission = new Submission(playerId, "SUPPRESS", CardGrade.II, affectedEventId, annihilatedId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.suppressShift(CardGrade.II)).willReturn(-20);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(4)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(((ParadoxResolved) payloads.get(0)).paradoxId()).isEqualTo(paradoxId);
        var detected = (ParadoxDetected) payloads.get(1);
        assertThat(detected.paradoxes()).singleElement().satisfies(paradox -> {
            assertThat(paradox.type()).isEqualTo(ParadoxType.DEAD_HEAT);
            assertThat(paradox.affectedEventId()).isEqualTo(affectedEventId);
        });
        var cascaded = (ParadoxCascaded) payloads.get(2);
        assertThat(cascaded.paradoxIds())
                .containsExactly(detected.paradoxes().getFirst().paradoxId());
        assertThat(cascaded.paradoxId()).isEqualTo(cascaded.paradoxIds().getFirst());
        assertThat(payloads).noneMatch(OutcomeApplied.class::isInstance);
        var barrier = (EraResolutionCompleted) payloads.get(3);
        assertThat(barrier.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(affectedEventId, 0, TerminalResolution.TerminalState.CASCADED, null));
    }

    @Test
    void closeEvent_twoFindingsOnOneEvent_onlyTheClearedOneIsResolved() {
        // Regression: a fresh finding is matched to an original one per finding, so clearing the erasure must
        // not also clear the chain conflict on the same annihilated outcome.
        var sagaId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var erasureParadoxId = UUID.randomUUID();
        var chainParadoxId = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId);
        var submission = new Submission(playerId, "SUPPRESS", CardGrade.II, affectedEventId, annihilatedId);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(
                        new PendingParadox(
                                erasureParadoxId,
                                ParadoxType.IMPOSSIBLE_ERASURE,
                                List.of(annihilatedId),
                                affectedEventId,
                                0),
                        new PendingParadox(
                                chainParadoxId,
                                ParadoxType.CHAIN_CONFLICT,
                                List.of(annihilatedId),
                                affectedEventId,
                                0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        givenActiveChainWithPendingLink(affectedEventId, annihilatedId);
        given(probabilityRules.suppressShift(CardGrade.II)).willReturn(-40);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        assertThat(payloads)
                .filteredOn(ParadoxResolved.class::isInstance)
                .extracting(p -> ((ParadoxResolved) p).paradoxId())
                .containsExactly(erasureParadoxId);
        assertThat(payloads)
                .filteredOn(ParadoxCascaded.class::isInstance)
                .extracting(p -> ((ParadoxCascaded) p).paradoxId())
                .containsExactly(chainParadoxId);
    }

    @Test
    void handlePlayerSubmitted_stabilizeTargetsEvent_resolvesDespiteStillParadoxed() {
        // STABILIZE suppresses re-detection entirely for its targeted event — the event resolves even
        // though its outcome state (untouched, since STABILIZE isn't a probability shift) would still trip
        // DEAD_HEAT on a fresh detection.
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var stabilizingPlayerId = UUID.randomUUID();
        var futureEvent = deadHeatFutureEvent(affectedEventId, outcomeIds, 20, 40, 40, false);
        var submission = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.DEAD_HEAT, outcomeIds.subList(1, 3), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(((ParadoxResolved) payloads.get(0)).resolvedByPlayerId()).isEqualTo(stabilizingPlayerId);
        assertThat(payloads.get(1)).isInstanceOf(OutcomeApplied.class);
        var barrier = (EraResolutionCompleted) payloads.get(2);
        assertThat(barrier.terminalResolutions())
                .extracting(TerminalResolution::terminalState)
                .containsExactly(TerminalResolution.TerminalState.OUTCOME_APPLIED);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "23, 0", "24, 1", "61, 1", "62, 2", "99, 2"})
    void handlePlayerSubmitted_stabilizeLeavesDeadHeat_weightedDrawOverUnchangedWeightsPicksWinner(
            long roll, int expectedWinnerIndex) {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var stabilizingPlayerId = UUID.randomUUID();
        var futureEvent = deadHeatFutureEvent(affectedEventId, outcomeIds, 24, 38, 38, false);
        var submission = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.DEAD_HEAT,
                        List.of(outcomeIds.get(1), outcomeIds.get(2)),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(random.nextLong()).willReturn(roll);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        var expectedWinnerId = outcomeIds.get(expectedWinnerIndex);
        var payloads = publishedPayloads(3);
        var resolved = (ParadoxResolved) payloads.get(0);
        assertThat(resolved.paradoxId()).isEqualTo(paradoxId);
        assertThat(resolved.resolvedByPlayerId()).isEqualTo(stabilizingPlayerId);
        var outcomeApplied = (OutcomeApplied) payloads.get(1);
        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(expectedWinnerId);
        assertThat(outcomeApplied.finalOutcomes())
                .extracting(Outcome::probability)
                .containsExactly(24, 38, 38);
        var barrier = (EraResolutionCompleted) payloads.get(2);
        assertThat(barrier.terminalResolutions())
                .containsExactly(new TerminalResolution(
                        affectedEventId, 0, TerminalResolution.TerminalState.OUTCOME_APPLIED, expectedWinnerId));
        then(weaverChainSaga).should().resolvePendingLink(GAME_ID, ERA_NUMBER, affectedEventId, expectedWinnerId);
        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "39, 1", "40, 2", "79, 2", "80, 1"})
    void handlePlayerSubmitted_stabilizeLeavesDeadHeatBesideAnnihilatedOutcome_tiedLeadersSplitTheDraw(
            long roll, int expectedWinnerIndex) {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var futureEvent = deadHeatFutureEvent(affectedEventId, outcomeIds, 20, 40, 40, true);
        var submission = new Submission(UUID.randomUUID(), "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.DEAD_HEAT,
                        List.of(outcomeIds.get(1), outcomeIds.get(2)),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(random.nextLong()).willReturn(roll);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        var outcomeApplied = (OutcomeApplied) publishedPayloads(3).get(1);
        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(outcomeIds.get(expectedWinnerIndex));
    }

    @Test
    void handlePlayerSubmitted_twoDeadHeatEventsOnlyOneStabilized_stabilizedResolvesOtherCascades() {
        var sagaId = UUID.randomUUID();
        var stabilizedParadoxId = UUID.randomUUID();
        var untouchedParadoxId = UUID.randomUUID();
        var stabilizedEventId = UUID.randomUUID();
        var untouchedEventId = UUID.randomUUID();
        var stabilizedOutcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var untouchedOutcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var stabilizingPlayerId = UUID.randomUUID();
        var submission = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, stabilizedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(
                        new PendingParadox(
                                stabilizedParadoxId,
                                ParadoxType.DEAD_HEAT,
                                List.of(stabilizedOutcomeIds.get(1), stabilizedOutcomeIds.get(2)),
                                stabilizedEventId,
                                0),
                        new PendingParadox(
                                untouchedParadoxId,
                                ParadoxType.DEAD_HEAT,
                                List.of(untouchedOutcomeIds.get(1), untouchedOutcomeIds.get(2)),
                                untouchedEventId,
                                1)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(stabilizedEventId))
                .willReturn(deadHeatFutureEvent(stabilizedEventId, stabilizedOutcomeIds, 24, 38, 38, false));
        given(futureEvents.findById(untouchedEventId))
                .willReturn(deadHeatFutureEvent(untouchedEventId, untouchedOutcomeIds, 24, 38, 38, false));
        given(random.nextLong()).willReturn(70L);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        var payloads = publishedPayloads(4);
        assertThat(((ParadoxResolved) payloads.get(0)).paradoxId()).isEqualTo(stabilizedParadoxId);
        assertThat(((OutcomeApplied) payloads.get(1)).winningOutcomeId()).isEqualTo(stabilizedOutcomeIds.get(2));
        assertThat(((ParadoxCascaded) payloads.get(2)).paradoxIds()).containsExactly(untouchedParadoxId);
        assertThat(((EraResolutionCompleted) payloads.get(3)).terminalResolutions())
                .containsExactly(
                        new TerminalResolution(
                                stabilizedEventId,
                                0,
                                TerminalResolution.TerminalState.OUTCOME_APPLIED,
                                stabilizedOutcomeIds.get(2)),
                        new TerminalResolution(untouchedEventId, 1, TerminalResolution.TerminalState.CASCADED, null));
        then(eraIndex).should().add(untouchedEventId, GAME_ID, ERA_NUMBER + 1, 1);
        then(eraIndex).should(never()).add(eq(stabilizedEventId), any(), anyInt(), anyInt());
    }

    @Test
    void handlePlayerSubmitted_stabilizeLeavesDeadHeatOnPendingChainLink_drawnWinnerConfirmsLink() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var pendingOutcomeId = outcomeIds.get(1);
        var futureEvent = deadHeatFutureEvent(affectedEventId, outcomeIds, 24, 38, 38, false);
        var submission = new Submission(UUID.randomUUID(), "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.DEAD_HEAT,
                        List.of(pendingOutcomeId, outcomeIds.get(2)),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        givenActiveChainWithPendingLink(affectedEventId, pendingOutcomeId);
        given(random.nextLong()).willReturn(30L);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(weaverChainSaga).should().resolvePendingLink(GAME_ID, ERA_NUMBER, affectedEventId, pendingOutcomeId);
        then(weaverChainSaga).should(never()).breakChainOnCascadedParadox(any(), anyInt(), any(), any(), any());
        assertThat(((OutcomeApplied) publishedPayloads(3).get(1)).winningOutcomeId())
                .isEqualTo(pendingOutcomeId);
    }

    @Test
    void handlePlayerSubmitted_stabilizeAfterPush_creditsStabilizingPlayer() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var stabilizingPlayerId = UUID.randomUUID();
        var futureEvent =
                impossibleErasureFutureEvent(affectedEventId, annihilatedId, secondOutcomeId, UUID.randomUUID());
        var push = new Submission(UUID.randomUUID(), "PUSH", CardGrade.I, affectedEventId, secondOutcomeId);
        var stabilize = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(push, stabilize),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, stabilize)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);
        given(probabilityRules.pushShift(CardGrade.I)).willReturn(10);
        given(probabilityRules.probabilityFloor()).willReturn(0);
        given(probabilityRules.probabilityCeiling()).willReturn(90);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, stabilize);

        var resolved = (ParadoxResolved) publishedPayloads(3).get(0);
        assertThat(resolved.resolvedByPlayerId()).isEqualTo(stabilizingPlayerId);
    }

    private List<Object> publishedPayloads(int expectedCount) {
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(expectedCount)).publish(captor.capture());
        return captor.getAllValues().stream()
                .<Object>map(TimelineEventEnvelope::payload)
                .toList();
    }

    /** The second and third outcomes were equalized by a Collide this era. */
    private static FutureEvent deadHeatFutureEvent(
            UUID eventId, List<UUID> outcomeIds, int first, int second, int third, boolean firstAnnihilated) {
        var outcomes = List.of(
                new Outcome(outcomeIds.get(0), "first", first, false, firstAnnihilated),
                new Outcome(outcomeIds.get(1), "second", second),
                new Outcome(outcomeIds.get(2), "third", third));
        return FutureEvent.replay(
                eventId,
                List.of(
                        new FutureEventDrafted(eventId, outcomes),
                        new OutcomesCollided(eventId, outcomes, outcomeIds.get(1), outcomeIds.get(2))));
    }

    @Test
    void handlePlayerSubmitted_stabilizeTargetsEventWhoseEligibleOutcomeHasNoWeight_cascadesInsteadOfThrowing() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var futureEvent = twoErasuresFutureEvent(affectedEventId, annihilatedId, secondOutcomeId, UUID.randomUUID());
        var submission = new Submission(UUID.randomUUID(), "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.IMPOSSIBLE_ERASURE,
                        List.of(annihilatedId, secondOutcomeId),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);
        var payloads = publishedPayloads(2);
        assertThat(((ParadoxCascaded) payloads.get(0)).paradoxIds()).containsExactly(paradoxId);
        assertThat(payloads).noneMatch(ParadoxResolved.class::isInstance).noneMatch(OutcomeApplied.class::isInstance);
    }

    @Test
    void handlePlayerSubmitted_stabilizeTargetsEventWithNoEligibleOutcome_cascadesInsteadOfThrowing() {
        // No outcome is eligible to resolve() — STABILIZE must not force that draw; fresh detection still
        // runs and the event cascades exactly as it would with no STABILIZE submitted.
        var sagaId = UUID.randomUUID();
        var stabilizingPlayerId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var futureEvent =
                allOutcomesAnnihilatedFutureEvent(affectedEventId, firstOutcomeId, secondOutcomeId, thirdOutcomeId);
        var submission = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId,
                        ParadoxType.IMPOSSIBLE_ERASURE,
                        List.of(firstOutcomeId, secondOutcomeId, thirdOutcomeId),
                        affectedEventId,
                        0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(stateManager).should().complete(phase);
        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        var cascaded = (ParadoxCascaded) payloads.get(0);
        assertThat(cascaded.paradoxIds()).containsExactly(paradoxId);
        assertThat(payloads).noneMatch(ParadoxResolved.class::isInstance).noneMatch(OutcomeApplied.class::isInstance);
        var barrier = (EraResolutionCompleted) payloads.get(1);
        assertThat(barrier.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(affectedEventId, 0, TerminalResolution.TerminalState.CASCADED, null));
    }

    @Test
    void handlePlayerSubmitted_detonateOnStillCascadingEvent_recordsDetonatingPlayer() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var detonatingPlayerId = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId);
        // DETONATE doesn't clear the paradox itself (no probability effect) — the event still cascades.
        var submission = new Submission(detonatingPlayerId, "DETONATE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(submission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, submission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, submission);

        then(eraIndex).should().add(affectedEventId, GAME_ID, ERA_NUMBER + 1, 0);
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var cascaded = (ParadoxCascaded) captor.getAllValues().get(0).payload();
        assertThat(cascaded.detonatedByPlayerIds()).containsExactly(detonatingPlayerId);
    }

    @Test
    void handlePlayerSubmitted_multipleDetonatesOnSameEvent_deduplicatedIntoOneSet() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var annihilatedId = UUID.randomUUID();
        var firstDetonator = UUID.randomUUID();
        var secondDetonator = UUID.randomUUID();
        var futureEvent = impossibleErasureFutureEvent(affectedEventId, annihilatedId);
        var firstSubmission = new Submission(firstDetonator, "DETONATE", CardGrade.I, affectedEventId, null);
        var secondSubmission = new Submission(secondDetonator, "DETONATE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.IMPOSSIBLE_ERASURE, List.of(annihilatedId), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(firstSubmission, secondSubmission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, secondSubmission)).willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, secondSubmission);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        var cascaded = (ParadoxCascaded) captor.getAllValues().get(0).payload();
        assertThat(cascaded.detonatedByPlayerIds()).containsExactlyInAnyOrder(firstDetonator, secondDetonator);
    }

    @Test
    void handlePlayerSubmitted_stabilizeAndDetonateSameEvent_stabilizeWinsNoCascadeNoDetonation() {
        var sagaId = UUID.randomUUID();
        var paradoxId = UUID.randomUUID();
        var affectedEventId = UUID.randomUUID();
        var outcomeIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var stabilizingPlayerId = UUID.randomUUID();
        var detonatingPlayerId = UUID.randomUUID();
        var futureEvent = deadHeatFutureEvent(affectedEventId, outcomeIds, 20, 40, 40, false);
        var stabilizeSubmission = new Submission(stabilizingPlayerId, "STABILIZE", CardGrade.I, affectedEventId, null);
        var detonateSubmission = new Submission(detonatingPlayerId, "DETONATE", CardGrade.I, affectedEventId, null);
        var phase = ParadoxResolutionPhase.withKnownRoster(
                sagaId,
                GAME_ID,
                ERA_NUMBER,
                ParadoxResolutionPhaseStatus.WAITING,
                List.of(new PendingParadox(
                        paradoxId, ParadoxType.DEAD_HEAT, outcomeIds.subList(1, 3), affectedEventId, 0)),
                List.of(),
                List.of(),
                List.of(stabilizeSubmission, detonateSubmission),
                clock.instant());

        given(stateManager.markSubmitted(GAME_ID, ERA_NUMBER, detonateSubmission))
                .willReturn(Optional.of(phase));
        given(futureEvents.findById(affectedEventId)).willReturn(futureEvent);

        saga.handlePlayerSubmitted(GAME_ID, ERA_NUMBER, detonateSubmission);

        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(3)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(payloads).noneMatch(ParadoxCascaded.class::isInstance);
        assertThat(payloads.get(0)).isInstanceOf(ParadoxResolved.class);
    }

    /** The annihilated outcome holds all the weight, leaving nothing to draw — triggers IMPOSSIBLE_ERASURE. */
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
                                new Outcome(annihilatedOutcomeId, "annihilated", 100, false, true),
                                new Outcome(secondOutcomeId, "second", 0),
                                new Outcome(thirdOutcomeId, "third", 0)))));
    }

    /** Two annihilated outcomes hold all the weight; the eligible third sits at 0 — trips IMPOSSIBLE_ERASURE. */
    private static FutureEvent twoErasuresFutureEvent(
            UUID eventId, UUID firstOutcomeId, UUID secondOutcomeId, UUID thirdOutcomeId) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(firstOutcomeId, "first", 60, false, true),
                                new Outcome(secondOutcomeId, "second", 40, false, true),
                                new Outcome(thirdOutcomeId, "third", 0)))));
    }

    /** All three outcomes annihilated — no eligible outcome remains, trips IMPOSSIBLE_ERASURE. */
    private static FutureEvent allOutcomesAnnihilatedFutureEvent(
            UUID eventId, UUID firstOutcomeId, UUID secondOutcomeId, UUID thirdOutcomeId) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(firstOutcomeId, "first", 50, false, true),
                                new Outcome(secondOutcomeId, "second", 30, false, true),
                                new Outcome(thirdOutcomeId, "third", 20, false, true)))));
    }

    /** Three distinct non-tied outcomes — triggers no paradox on its own, only via chain state. */
    private static FutureEvent cleanFutureEvent(UUID eventId, UUID firstOutcomeId, UUID secondOutcomeId) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(firstOutcomeId, "first", 40),
                                new Outcome(secondOutcomeId, "second", 35),
                                new Outcome(UUID.randomUUID(), "third", 25)))));
    }

    private void givenActiveChainWithPendingLink(UUID eventId, UUID pendingOutcomeId) {
        var chainId = UUID.randomUUID();
        given(chainSagas.findOpenByGame(GAME_ID))
                .willReturn(List.of(new WeaverChainSagaState(
                        chainId, GAME_ID, UUID.randomUUID(), WeaverChainSagaStatus.OPEN, false, null, null)));
        given(chains.findById(chainId)).willReturn(chainWithPendingLink(chainId, eventId, pendingOutcomeId));
    }

    private static WeaverChain chainWithPendingLink(UUID chainId, UUID eventId, UUID outcomeId) {
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), GAME_ID),
                        new io.github.temporalrift.timeline.domain.event.ChainLinkThreaded(
                                chainId, eventId, outcomeId, ERA_NUMBER)));
    }
}
