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
        given(rules.pushShift()).willReturn(20);
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
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayed("PUSH", eventId, null, target, at(0)),
                        specialAction("SEAL", eventId, target, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, target)).isEqualTo(50);
        assertThat(futureEvent.sealBreach()).isTrue();
    }

    @Test
    void replay_nullifyCancelsPrecedingPush_neverReadsTheFutureEvent() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("PUSH", eventId, null, a, at(0)), cardPlayedType("NULLIFY", at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Preemptive cancellation (design.md Decision 2): the cancelled PUSH is skipped entirely, never
        // applied-then-reversed — proven here by the fact its target is never even looked up.
        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_nullifyCancelsPrecedingSeal_neverReadsTheFutureEvent() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(specialAction("SEAL", eventId, target, at(0)), cardPlayedType("NULLIFY", at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    void replay_chainedNullify_targetsNearestStillLiveAction() {
        // T1 PUSH, T2 NULLIFY (cancels T1), T3 NULLIFY (targets T2, not the already-cancelled T1) — a
        // second PUSH submitted after both must still apply normally, proving T1 stayed cancelled.
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift()).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(
                        cardPlayed("PUSH", eventId, null, a, at(0)),
                        cardPlayedType("NULLIFY", at(1)),
                        cardPlayedType("NULLIFY", at(2)),
                        cardPlayed("PUSH", eventId, null, a, at(3))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Only the second PUSH (at(3)) took effect: +10 once, not zero and not twice.
        assertThat(probabilityOf(futureEvent, a)).isEqualTo(60);
    }

    @Test
    void replay_amplify_doublesSubmissionOrderNextShifterEvenIfDeliveredLater() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift()).willReturn(10);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        // Buffer (arrival) order: PUSH before AMPLIFY, but AMPLIFY's timestamp (0) precedes the PUSH's (1).
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("PUSH", eventId, null, a, at(1)), cardPlayedType("AMPLIFY", at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, a)).isEqualTo(70);
    }

    @Test
    void replay_corruptInvertsCorrelatedPush_intoSuppress_andConfirmsTookEffect() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(target, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.suppressShift()).willReturn(-20);
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
    void replay_redirect_retargetsLastShiftThisRound() {
        var eventId = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var futureEvent = drafted(eventId, outcome(a, 50), outcome(b, 30), outcome(c, 20));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift()).willReturn(20);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(List.of(cardPlayed("PUSH", eventId, null, a, at(0)), redirect(eventId, b, at(1))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // The PUSH's effect on `a` is undone and reapplied to `b` instead — `b` receives the same +20 `a`
        // would have, with `a`/`c` now absorbing the redistribution instead of `b`/`c` (exact proportional
        // math is FutureEventTest's concern; this only asserts the redirect actually moved the target).
        assertThat(probabilityOf(futureEvent, b)).isEqualTo(50);
        assertThat(probabilityOf(futureEvent, a) + probabilityOf(futureEvent, b) + probabilityOf(futureEvent, c))
                .isEqualTo(100);
    }

    @Test
    void replay_redirect_noPriorShiftThisRound_isNoOp() {
        var eventId = UUID.randomUUID();
        var b = UUID.randomUUID();
        given(buffer.findByRound(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(redirect(eventId, b, at(0))));

        handler.replay(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then(futureEvents).should(never()).findById(any());
        then(futureEvents).should(never()).append(any(), any());
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
        given(rules.pushShift()).willReturn(10);
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

    private static BufferedAction cardPlayedBy(
            UUID playerId,
            String cardType,
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
                occurredAt,
                UUID.randomUUID());
    }

    private static BufferedAction cardPlayedType(String cardType, Instant occurredAt) {
        return cardPlayedBy(UUID.randomUUID(), cardType, null, null, null, occurredAt);
    }

    private static BufferedAction redirect(UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return cardPlayed("REDIRECT", targetEventId, null, targetOutcomeId, occurredAt);
    }

    private static BufferedAction specialAction(
            String specialAction, UUID targetEventId, UUID targetOutcomeId, Instant occurredAt) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                specialAction,
                UUID.randomUUID(),
                null,
                targetEventId,
                null,
                targetOutcomeId,
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
                occurredAt,
                UUID.randomUUID());
    }
}
