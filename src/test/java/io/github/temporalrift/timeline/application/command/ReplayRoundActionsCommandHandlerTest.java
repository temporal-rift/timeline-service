package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.BandedProbabilityPublished;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.ResolutionWarning;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityBand;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

@ExtendWith(MockitoExtension.class)
class ReplayRoundActionsCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final int ROUND_NUMBER = 1;
    private static final Instant BASE_TIME = Instant.parse("2026-08-09T00:00:00Z");

    @Mock
    RoundActionBufferPort buffer;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    ProbabilityRulesPort rules;

    @Mock
    ProbabilityBandRulesPort bandRules;

    @Mock
    TimelineEventPublisher publisher;

    private final Clock clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);

    private ReplayRoundActionsCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReplayRoundActionsCommandHandler(
                buffer, futureEvents, eraIndex, rules, bandRules, publisher, clock);
    }

    @Test
    void replay_emptyBuffer_doesNothing() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of());

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).append(any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void replay_push_appliesConfiguredMagnitude() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("PUSH", eventId, null, a, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(70);
    }

    @Test
    void replay_sealBeforePush_alwaysPrecedesRegardlessOfBufferOrder() {
        // Buffer (arrival) order has PUSH before SEAL, but SEAL is tier 2 and PUSH is tier 6 — SEAL must
        // still apply first.
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var other1 = UUID.randomUUID();
        var other2 = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(target, 50), outcome(other1, 30), outcome(other2, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayed("PUSH", eventId, null, target, at(0)),
                        specialAction("SEAL", eventId, target, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, target)).isEqualTo(50);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_nullifyCancelsNamedPushRegardlessOfSubmissionOrder_neverReadsTheFutureEvent() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var nullifyingPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(nullifyingPlayer, "NULLIFY", pushingPlayer, null, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // NULLIFY was submitted first, but named-player correlation still skips PUSH entirely.
        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_nullifyCancelsNamedSealRegardlessOfSubmissionOrder_neverReadsTheFutureEvent() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var sealingPlayer = UUID.randomUUID();
        var nullifyingPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialActionBy(sealingPlayer, "SEAL", eventId, target, at(1)),
                        playerTargetedCard(nullifyingPlayer, "NULLIFY", sealingPlayer, null, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_mutualNullify_isDeterministicAndDoesNotCancelAnotherPlayersAction() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        playerTargetedCard(playerB, "NULLIFY", playerA, null, at(2)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(playerA, "NULLIFY", playerB, null, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(60);
    }

    @Test
    void replay_twoNullifiesNamingSameAction_cancelOnlyThatAction() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, outcomeId, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "NULLIFY", pushingPlayer, null, at(1)),
                        playerTargetedCard(UUID.randomUUID(), "NULLIFY", pushingPlayer, null, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_amplifyDoublesNamedShifterRegardlessOfSubmissionOrder() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var amplifyingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.amplifyMultiplier(CardGrade.II)).willReturn(2.0);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        playerTargetedCard(amplifyingPlayer, "AMPLIFY", pushingPlayer, null, at(1)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(70);
    }

    @Test
    void replay_amplify_higherGradeAppliesLargerMultiplier() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var amplifyingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.amplifyMultiplier(CardGrade.III)).willReturn(3.0);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(amplifyingPlayer, "AMPLIFY", CardGrade.III, pushingPlayer, null, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // 10 * 3.0 (grade III AMPLIFY) = 30, larger than the grade II baseline's 10 * 2.0 = 20.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(80);
    }

    @Test
    void replay_amplifyTargetingNonShifter_isNoOp() {
        var targetPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(targetPlayer, "SCAN", UUID.randomUUID(), null, null, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "AMPLIFY", targetPlayer, null, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_duplicateActionsByPlayer_useTheSameSelectedActionForAllModifiers() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var targetPlayer = UUID.randomUUID();
        var futureEvent = drafted(
                eventId, outcome(outcomeId, 50), outcome(UUID.randomUUID(), 30), outcome(UUID.randomUUID(), 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(targetPlayer, "SCAN", UUID.randomUUID(), null, null, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "AMPLIFY", targetPlayer, null, at(1)),
                        cardPlayedBy(targetPlayer, "PUSH", eventId, null, outcomeId, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, outcomeId)).isEqualTo(60);
    }

    @Test
    void replay_amplifyTargetingCancelledShifter_doesNotTransferToAnotherPlayer() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var cancelledPlayer = UUID.randomUUID();
        var livePlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(cancelledPlayer, "PUSH", eventId, null, a, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "AMPLIFY", cancelledPlayer, null, at(1)),
                        playerTargetedCard(UUID.randomUUID(), "NULLIFY", cancelledPlayer, null, at(2)),
                        cardPlayedBy(livePlayer, "PUSH", eventId, null, a, at(3))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(60);
    }

    @Test
    void replay_corruptInvertsCorrelatedPush_intoSuppress_andConfirmsTookEffect() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(target, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.suppressShift(CardGrade.II)).willReturn(-20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        var pushingPlayer = UUID.randomUUID();
        var corruptingPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, target, at(0)),
                        corrupt(corruptingPlayer, pushingPlayer, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, target)).isEqualTo(30);
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var confirmed = (CorruptInversionConfirmed) captor.getValue().payload();
        assertThat(confirmed.tookEffect()).isTrue();
        assertThat(confirmed.corruptingPlayerId()).isEqualTo(corruptingPlayer);
        assertThat(confirmed.targetEventId()).isEqualTo(eventId);
        assertThat(confirmed.targetOutcomeId()).isEqualTo(target);
    }

    @Test
    void replay_corruptCorrelatedCardSealed_confirmsTookEffectFalse() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(target, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.suppressShift(CardGrade.II)).willReturn(-20);
        var pushingPlayer = UUID.randomUUID();
        var corruptingPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialAction("SEAL", eventId, target, at(0)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, target, at(1)),
                        corrupt(corruptingPlayer, pushingPlayer, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, target)).isEqualTo(50);
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var confirmed = (CorruptInversionConfirmed) captor.getValue().payload();
        assertThat(confirmed.tookEffect()).isFalse();
    }

    @Test
    void replay_corruptWithNoMatchingCard_noConfirmationPublished() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(corrupt(UUID.randomUUID(), UUID.randomUUID(), at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(publisher).should(never()).publish(any());
    }

    @Test
    void replay_mimicCopiesCorrelatedPush_appliesTwoIndependentPushes() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        var pushingPlayer = UUID.randomUUID();
        var mimicPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(0)),
                        mimic(mimicPlayer, eventId, a, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Two independent 20-point pushes from 50 clamp exactly at the configured ceiling of 90.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(90);
        assertThat(probabilityOf(futureEvent, a) + probabilityOf(futureEvent, b) + probabilityOf(futureEvent, c))
                .isEqualTo(100);
    }

    @Test
    void replay_mimicSelectsEarliestOfMultipleQualifyingCards() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.swingShift(CardGrade.II)).willReturn(15);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        var pushingPlayer = UUID.randomUUID();
        var swingingPlayer = UUID.randomUUID();
        var mimicPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(0)),
                        cardPlayedBy(swingingPlayer, "SWING", eventId, c, a, at(1)),
                        mimic(mimicPlayer, eventId, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // If MIMIC had instead correlated to the later SWING, `b` would be untouched by it — the exact
        // values below only result from copying the earliest candidate, the PUSH.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(90);
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(6);
        assertThat(probabilityOf(futureEvent, c)).isEqualTo(4);
    }

    @Test
    void replay_mimicDoesNotCopyANullifiedCard() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var nullifyingPlayer = UUID.randomUUID();
        var mimicPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(0)),
                        playerTargetedCard(nullifyingPlayer, "NULLIFY", pushingPlayer, null, at(1)),
                        mimic(mimicPlayer, eventId, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // The only candidate PUSH was cancelled by NULLIFY, so MIMIC has nothing to copy — proven by the
        // fact its target FutureEvent is never even looked up.
        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_mimicAgainstSealedTarget_setsSealBreachInsteadOfApplying() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        var pushingPlayer = UUID.randomUUID();
        var mimicPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialAction("SEAL", eventId, a, at(0)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        mimic(mimicPlayer, eventId, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(50);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_mimicWithNoCorrelatedCard_hasNoEffect() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(mimic(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_rallyBoostsRound1PushToDeclaredOutcome() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.rallyMultiplier()).willReturn(1.5);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)), cardPlayed("PUSH", eventId, null, a, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // 20 * 1.5 = 30, not the unboosted 20.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(80);
    }

    @Test
    void replay_rallyBoostsSwingIntoDeclaredOutcome() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.swingShift(CardGrade.II)).willReturn(10);
        given(rules.rallyMultiplier()).willReturn(1.5);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)), cardPlayed("SWING", eventId, c, a, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // 10 * 1.5 = 15 moved from c into the declared outcome a, not the unboosted 10.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(65);
        assertThat(probabilityOf(futureEvent, c)).isEqualTo(5);
    }

    @Test
    void replay_rallyDoesNotBoostASwingAwayFromTheDeclaredOutcome() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.swingShift(CardGrade.II)).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)), cardPlayed("SWING", eventId, a, b, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Unboosted 10 moved away from the declared outcome a, not a boosted 15.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(40);
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(40);
    }

    @Test
    void replay_rallyDoesNotBoostASuppressTargetingTheDeclaredOutcome() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.suppressShift(CardGrade.II)).willReturn(-20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)), cardPlayed("SUPPRESS", eventId, null, a, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Unboosted -20, not a boosted -30.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(30);
    }

    @Test
    void replay_rallyBoostedPushAgainstSealedTarget_setsSealBreachInsteadOfApplying() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialAction("SEAL", eventId, a, at(0)),
                        rally(UUID.randomUUID(), eventId, a, at(1)),
                        cardPlayed("PUSH", eventId, null, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(50);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_rallyDoesNotBoostRound2() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER)).willReturn(List.of());
        // A RALLY entry should never reach round 2's buffer in production, but the replay handler still
        // must not consult it defensively (design.md/spec: Round 1 only).
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, 2))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)), cardPlayed("PUSH", eventId, null, a, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, 2);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(70);
    }

    @Test
    void replay_mimicCopyLandingOnDeclaredOutcomeIsBoosted() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.rallyMultiplier()).willReturn(1.5);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        var pushingPlayer = UUID.randomUUID();
        var mimicPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        mimic(mimicPlayer, eventId, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Both the ordinary PUSH and MIMIC's copy are boosted: 50 + 30 + 30 clamped at the 90 ceiling.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(90);
    }

    @Test
    void replay_twoRallyDeclarationsOnSameOutcome_boostOnce() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.rallyMultiplier()).willReturn(1.5);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        rally(UUID.randomUUID(), eventId, a, at(0)),
                        rally(UUID.randomUUID(), eventId, a, at(1)),
                        cardPlayed("PUSH", eventId, null, a, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Still a single 1.5x boost (30), not 2.25x (45) from two declarations.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(80);
    }

    @Test
    void replay_collide_equalizesTwoOutcomes() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("COLLIDE", eventId, a, b, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(40);
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(40);
    }

    @Test
    void replay_redirectRetargetsNamedPlayersShiftRegardlessOfSubmissionOrder() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var redirectingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(redirectingPlayer, "REDIRECT", pushingPlayer, b, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // REDIRECT was submitted before PUSH but still changes the named player's destination to b.
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(50);
        assertThat(probabilityOf(futureEvent, a) + probabilityOf(futureEvent, b) + probabilityOf(futureEvent, c))
                .isEqualTo(100);
    }

    @Test
    void replay_redirectTargetingSkippedPlayer_isNoOp() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(playerTargetedCard(
                        UUID.randomUUID(), "REDIRECT", UUID.randomUUID(), UUID.randomUUID(), at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_redirectTargetingNonShifter_isNoOp() {
        var targetPlayer = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(targetPlayer, "SCAN", UUID.randomUUID(), null, null, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "REDIRECT", targetPlayer, UUID.randomUUID(), at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_redirectIntoSealedOutcome_recordsSealBreachWithoutChangingProbabilities() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialAction("SEAL", eventId, b, at(0)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(UUID.randomUUID(), "REDIRECT", pushingPlayer, b, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(50);
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(30);
        assertThat(probabilityOf(futureEvent, c)).isEqualTo(20);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_redirectToUnknownOutcome_keepsTheOriginalDestination() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var unknownOutcomeId = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(0)),
                        playerTargetedCard(UUID.randomUUID(), "REDIRECT", pushingPlayer, unknownOutcomeId, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(70);
    }

    @Test
    void replay_amplifiedShiftIntoSealedOutcome_recordsSealBreachWithoutChangingProbabilities() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var pushingPlayer = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(20);
        given(rules.amplifyMultiplier(CardGrade.II)).willReturn(2.0);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        specialAction("SEAL", eventId, a, at(0)),
                        cardPlayedBy(pushingPlayer, "PUSH", eventId, null, a, at(1)),
                        playerTargetedCard(UUID.randomUUID(), "AMPLIFY", pushingPlayer, null, at(2))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(50);
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(30);
        assertThat(probabilityOf(futureEvent, c)).isEqualTo(20);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_stall_marksEventStalled() {
        var eventId = UUID.randomUUID();
        var futureEvent = drafted(
                eventId,
                outcome(UUID.randomUUID(), 50),
                outcome(UUID.randomUUID(), 30),
                outcome(UUID.randomUUID(), 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("STALL", eventId, null, null, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(futureEvent.stalled()).isTrue();
    }

    @Test
    void replay_invalidProbabilitySum_publishesResolutionFailed() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        // Outcomes sum to 95, not 100 — a pre-existing invalid state the guard must catch.
        var futureEvent = drafted(eventId, outcome(a, 45), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift(CardGrade.II)).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("PUSH", eventId, null, a, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var failed = (ResolutionFailed) captor.getValue().payload();
        assertThat(failed.gameId()).isEqualTo(GAME_ID);
        assertThat(failed.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(failed.affectedEventId()).isEqualTo(eventId);
    }

    @Test
    void replay_identicalOccurredAtTieInRemainingTier_publishesResolutionWarning() {
        var eventId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();
        var tiedInstant = at(0);
        given(futureEvents.findById(any()))
                .willReturn(drafted(
                        eventId1,
                        outcome(UUID.randomUUID(), 33),
                        outcome(UUID.randomUUID(), 33),
                        outcome(UUID.randomUUID(), 34)));
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayed("STALL", eventId1, null, null, tiedInstant),
                        cardPlayed("STALL", eventId2, null, null, tiedInstant)));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        assertThat(captor.getValue().payload()).isInstanceOf(ResolutionWarning.class);
    }

    @Test
    void replay_round2_publishesBandedProbabilityForActiveEvents() {
        var eventId = UUID.randomUUID();
        var lowOutcome = UUID.randomUUID();
        var mediumOutcome = UUID.randomUUID();
        var highOutcome = UUID.randomUUID();
        var futureEvent =
                drafted(eventId, outcome(lowOutcome, 5), outcome(mediumOutcome, 33), outcome(highOutcome, 62));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(bandRules.bandLowMax()).willReturn(30);
        given(bandRules.bandMediumMax()).willReturn(60);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, 2)).willReturn(List.of());

        handler.replay(GAME_ID, ERA_NUMBER, 2);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var payload = (BandedProbabilityPublished) captor.getValue().payload();
        assertThat(payload.gameId()).isEqualTo(GAME_ID);
        assertThat(payload.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(payload.eventStates()).hasSize(1);
        var eventState = payload.eventStates().getFirst();
        assertThat(eventState.eventId()).isEqualTo(eventId);
        assertThat(bandOf(eventState, lowOutcome)).isEqualTo(ProbabilityBand.LOW);
        assertThat(bandOf(eventState, mediumOutcome)).isEqualTo(ProbabilityBand.MEDIUM);
        assertThat(bandOf(eventState, highOutcome)).isEqualTo(ProbabilityBand.HIGH);
    }

    @Test
    void replay_round2_excludesStalledEvents() {
        var activeEventId = UUID.randomUUID();
        var stalledEventId = UUID.randomUUID();
        var activeEvent = drafted(
                activeEventId,
                outcome(UUID.randomUUID(), 33),
                outcome(UUID.randomUUID(), 33),
                outcome(UUID.randomUUID(), 34));
        var stalledEvent = drafted(
                stalledEventId,
                outcome(UUID.randomUUID(), 33),
                outcome(UUID.randomUUID(), 33),
                outcome(UUID.randomUUID(), 34));
        stalledEvent.markStalled();
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(activeEventId, 0), new IndexedEventId(stalledEventId, 1)));
        given(futureEvents.findById(activeEventId)).willReturn(activeEvent);
        given(futureEvents.findById(stalledEventId)).willReturn(stalledEvent);
        given(bandRules.bandLowMax()).willReturn(30);
        given(bandRules.bandMediumMax()).willReturn(60);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, 2)).willReturn(List.of());

        handler.replay(GAME_ID, ERA_NUMBER, 2);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var payload = (BandedProbabilityPublished) captor.getValue().payload();
        assertThat(payload.eventStates())
                .extracting(BandedProbabilityPublished.EventState::eventId)
                .containsExactly(activeEventId);
    }

    @Test
    void replay_round1_doesNotPublishBandedProbability() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of());

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(eraIndex).should(never()).findByGameIdAndEraNumber(any(), anyInt());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void replay_round3_doesNotPublishBandedProbability() {
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, 3)).willReturn(List.of());

        handler.replay(GAME_ID, ERA_NUMBER, 3);

        then(eraIndex).should(never()).findByGameIdAndEraNumber(any(), anyInt());
        then(publisher).should(never()).publish(any());
    }

    private static FutureEvent drafted(UUID id, Outcome... outcomes) {
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, List.of(outcomes))));
    }

    private static Outcome outcome(UUID outcomeId, int probability) {
        return new Outcome(outcomeId, "outcome", probability);
    }

    private static int probabilityOf(FutureEvent futureEvent, UUID outcomeId) {
        return futureEvent.outcomes().stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow()
                .probability();
    }

    private static ProbabilityBand bandOf(BandedProbabilityPublished.EventState eventState, UUID outcomeId) {
        return eventState.outcomes().stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow()
                .band();
    }

    private static Instant at(long secondsOffset) {
        return BASE_TIME.plusSeconds(secondsOffset);
    }

    private static BufferedAction cardPlayed(
            String cardType, UUID targetEventId, UUID sourceOutcomeId, UUID targetOutcomeId, Instant occurredAt) {
        return cardPlayedBy(UUID.randomUUID(), cardType, targetEventId, sourceOutcomeId, targetOutcomeId, occurredAt);
    }

    private static BufferedAction cardPlayedGraded(
            String cardType,
            CardGrade grade,
            UUID targetEventId,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            Instant occurredAt) {
        return cardPlayedByGraded(
                UUID.randomUUID(), cardType, grade, targetEventId, sourceOutcomeId, targetOutcomeId, occurredAt);
    }

    private static BufferedAction cardPlayedBy(
            UUID playerId,
            String cardType,
            UUID targetEventId,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            Instant occurredAt) {
        return cardPlayedByGraded(
                playerId, cardType, CardGrade.II, targetEventId, sourceOutcomeId, targetOutcomeId, occurredAt);
    }

    private static BufferedAction cardPlayedByGraded(
            UUID playerId,
            String cardType,
            CardGrade grade,
            UUID targetEventId,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            Instant occurredAt) {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                cardType,
                null,
                playerId,
                UUID.randomUUID(),
                targetEventId,
                sourceOutcomeId,
                targetOutcomeId,
                null,
                grade,
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction cardPlayedType(String cardType, Instant occurredAt) {
        return cardPlayedBy(UUID.randomUUID(), cardType, null, null, null, occurredAt);
    }

    private static BufferedAction playerTargetedCard(
            UUID playerId,
            String cardType,
            CardGrade grade,
            UUID targetPlayerId,
            UUID targetOutcomeId,
            Instant occurredAt) {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                cardType,
                null,
                playerId,
                UUID.randomUUID(),
                null,
                null,
                targetOutcomeId,
                targetPlayerId,
                grade,
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction playerTargetedCard(
            UUID playerId, String cardType, UUID targetPlayerId, UUID targetOutcomeId, Instant occurredAt) {
        return playerTargetedCard(playerId, cardType, CardGrade.II, targetPlayerId, targetOutcomeId, occurredAt);
    }

    private static BufferedAction specialAction(
            String specialAction, UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return specialActionBy(UUID.randomUUID(), specialAction, targetEventId, targetOutcomeId, occurredAt);
    }

    private static BufferedAction specialActionBy(
            UUID playerId, String specialAction, UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                specialAction,
                playerId,
                null,
                targetEventId,
                null,
                targetOutcomeId,
                null,
                null,
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction corrupt(UUID corruptingPlayerId, UUID targetPlayerId, Instant occurredAt) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                "CORRUPT",
                corruptingPlayerId,
                null,
                null,
                null,
                null,
                targetPlayerId,
                null,
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction mimic(UUID playerId, UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                "MIMIC",
                playerId,
                null,
                targetEventId,
                null,
                targetOutcomeId,
                null,
                null,
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction rally(UUID playerId, UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                "RALLY",
                playerId,
                null,
                targetEventId,
                null,
                targetOutcomeId,
                null,
                null,
                occurredAt,
                UUID.randomUUID());
    }
}
