package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase.CardModifier;
import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase;
import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase.SpecialAction;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolvePendingCorruptUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class CardPlayedAndResolutionKafkaConsumerTest {

    private static final String CARD_PLAYED_EVENT_TYPE = "CardPlayed";
    private static final String CARD_PLAYED_CONSUMER = "futureevent.card-played";
    private static final String SPECIAL_ACTION_PLAYED_EVENT_TYPE = "SpecialActionPlayed";
    private static final String SPECIAL_ACTION_PLAYED_CONSUMER = "futureevent.special-action-played";
    private static final String ACTION_ROUND_CLOSED_EVENT_TYPE = "ActionRoundClosed";
    private static final String ACTION_ROUND_CLOSED_CONSUMER = "futureevent.action-round-closed";
    private static final String RESOLUTION_STARTED_EVENT_TYPE = "ResolutionStarted";
    private static final String RESOLUTION_STARTED_CONSUMER = "futureevent.resolution-started";
    private static final String PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE = "ParadoxResolutionCardPlayed";
    private static final String PARADOX_RESOLUTION_CARD_PLAYED_CONSUMER = "futureevent.paradox-resolution-card-played";
    private static final int ERA_NUMBER = 2;
    private static final int ROUND_NUMBER = 3;

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    ApplyProbabilityShiftUseCase applyProbabilityShift;

    @Mock
    PlayCardModifierUseCase playCardModifier;

    @Mock
    PlaySpecialActionUseCase playSpecialAction;

    @Mock
    ResolvePendingCorruptUseCase resolvePendingCorrupt;

    @Mock
    ResolveEraUseCase resolveEra;

    @Mock
    PlayParadoxResolutionCardUseCase playParadoxResolutionCard;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    CardPlayedAndResolutionKafkaConsumer consumer;

    @Test
    @DisplayName("PUSH — applies a Push shift to the target event")
    void handle_push_appliesShift() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "PUSH", null, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift)
                .should()
                .apply(
                        eq(payload.gameId()),
                        eq(ERA_NUMBER),
                        eq(ROUND_NUMBER),
                        eq(payload.playerId()),
                        eq(targetEventId),
                        shiftCaptor.capture());
        var shift = (ProbabilityShift.Push) shiftCaptor.getValue();
        assertThat(shift.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("SUPPRESS — applies a Suppress shift to the target event")
    void handle_suppress_appliesShift() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "SUPPRESS", null, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift)
                .should()
                .apply(
                        eq(payload.gameId()),
                        eq(ERA_NUMBER),
                        eq(ROUND_NUMBER),
                        eq(payload.playerId()),
                        eq(targetEventId),
                        shiftCaptor.capture());
        var shift = (ProbabilityShift.Suppress) shiftCaptor.getValue();
        assertThat(shift.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("SWING — applies a Swing shift with source and target outcomes")
    void handle_swing_appliesShift() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var sourceOutcomeId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "SWING", sourceOutcomeId, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift)
                .should()
                .apply(
                        eq(payload.gameId()),
                        eq(ERA_NUMBER),
                        eq(ROUND_NUMBER),
                        eq(payload.playerId()),
                        eq(targetEventId),
                        shiftCaptor.capture());
        var shift = (ProbabilityShift.Swing) shiftCaptor.getValue();
        assertThat(shift.sourceOutcomeId()).isEqualTo(sourceOutcomeId);
        assertThat(shift.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("AMPLIFY — routed to PlayCardModifierUseCase, never applies a shift directly")
    void handle_amplify_playsCardModifier() {
        var eventId = UUID.randomUUID();
        var payload = cardPlayed(UUID.randomUUID(), "AMPLIFY", null, UUID.randomUUID());
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(playCardModifier).should().play(new CardModifier.Amplify(payload.gameId(), ERA_NUMBER, ROUND_NUMBER));
        then(applyProbabilityShift).should(never()).apply(any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("NULLIFY — routed to PlayCardModifierUseCase")
    void handle_nullify_playsCardModifier() {
        var eventId = UUID.randomUUID();
        var payload = cardPlayed(UUID.randomUUID(), "NULLIFY", null, UUID.randomUUID());
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(playCardModifier).should().play(new CardModifier.Nullify(payload.gameId(), ERA_NUMBER, ROUND_NUMBER));
    }

    @Test
    @DisplayName("REDIRECT — routed to PlayCardModifierUseCase with target event and outcome")
    void handle_redirect_playsCardModifier() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "REDIRECT", null, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(playCardModifier)
                .should()
                .play(new CardModifier.Redirect(
                        payload.gameId(), ERA_NUMBER, ROUND_NUMBER, targetEventId, targetOutcomeId));
    }

    @Test
    @DisplayName("STALL — routed to PlayCardModifierUseCase with target event")
    void handle_stall_playsCardModifier() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "STALL", null, UUID.randomUUID());
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(playCardModifier)
                .should()
                .play(new CardModifier.Stall(payload.gameId(), ERA_NUMBER, ROUND_NUMBER, targetEventId));
    }

    @Test
    @DisplayName("info/no-op card types — routed to PlayCardModifierUseCase as NoOp")
    void handle_infoCardTypes_playsNoOpCardModifier() {
        for (var cardType : new String[] {"INTERCEPT", "SCAN", "TRACE", "DECOY", "JAM"}) {
            var eventId = UUID.randomUUID();
            var payload = cardPlayed(UUID.randomUUID(), cardType, null, UUID.randomUUID());
            given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

            consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

            then(playCardModifier).should().play(new CardModifier.NoOp(payload.gameId(), ERA_NUMBER, ROUND_NUMBER));
        }
    }

    @Test
    @DisplayName("null card type — claims the event but triggers no use case, no NullPointerException")
    void handle_nullCardType_claimsButDoesNothing() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), null, null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(applyProbabilityShift).should(never()).apply(any(), anyInt(), anyInt(), any(), any(), any());
        then(playCardModifier).should(never()).play(any());
    }

    @Test
    @DisplayName("CardPlayed duplicate eventId — no effect applied")
    void handle_cardPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(applyProbabilityShift).should(never()).apply(any(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("ResolutionStarted matching event type — triggers resolution for the envelope's gameId/eraNumber")
    void handle_resolutionStarted_triggersResolution() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        given(processedEvents.claim(eventId, RESOLUTION_STARTED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(gameId, eraNumber), eventId, RESOLUTION_STARTED_EVENT_TYPE, 1));

        then(resolveEra).should().resolve(gameId, eraNumber);
    }

    @Test
    @DisplayName("ResolutionStarted duplicate eventId — no resolution triggered")
    void handle_resolutionStartedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, RESOLUTION_STARTED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(UUID.randomUUID(), 1), eventId, RESOLUTION_STARTED_EVENT_TYPE, 1));

        then(resolveEra).should(never()).resolve(any(), anyInt());
    }

    @Test
    @DisplayName("unrelated event type — ignored, neither shift nor resolution triggered")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), UUID.randomUUID(), "EraStarted", 1));

        then(processedEvents).should(never()).claim(any(), any());
        then(applyProbabilityShift).should(never()).apply(any(), anyInt(), anyInt(), any(), any(), any());
        then(resolveEra).should(never()).resolve(any(), anyInt());
    }

    @Test
    @DisplayName("CardPlayed then ResolutionStarted on the same consumer instance apply in that order")
    void handle_cardPlayedThenResolutionStarted_appliesShiftBeforeResolving() {
        var cardEventId = UUID.randomUUID();
        var resolutionEventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        given(processedEvents.claim(cardEventId, CARD_PLAYED_CONSUMER)).willReturn(true);
        given(processedEvents.claim(resolutionEventId, RESOLUTION_STARTED_CONSUMER))
                .willReturn(true);

        // A single consumer instance processing records sequentially, one KafkaListener invocation at a
        // time — this is what actually prevents the cross-consumer-group race (design.md revision):
        // there is no second thread/group that could observe pre-shift state.
        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(targetEventId, "PUSH", null, targetOutcomeId), cardEventId, CARD_PLAYED_EVENT_TYPE, 1));
        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(gameId, 1), resolutionEventId, RESOLUTION_STARTED_EVENT_TYPE, 1));

        var order = inOrder(applyProbabilityShift, resolveEra);
        order.verify(applyProbabilityShift).apply(any(), anyInt(), anyInt(), any(), eq(targetEventId), any());
        order.verify(resolveEra).resolve(eq(gameId), eq(1));
    }

    @Test
    @DisplayName("SEAL — routed to PlaySpecialActionUseCase")
    void handle_seal_playsSpecialAction() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = specialActionPlayed("SEAL", targetEventId, targetOutcomeId, null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        then(playSpecialAction).should().play(new SpecialAction.Seal(targetEventId, targetOutcomeId));
    }

    @Test
    @DisplayName("ANNIHILATE — routed to PlaySpecialActionUseCase")
    void handle_annihilate_playsSpecialAction() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = specialActionPlayed("ANNIHILATE", targetEventId, targetOutcomeId, null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        then(playSpecialAction).should().play(new SpecialAction.Annihilate(targetEventId, targetOutcomeId));
    }

    @Test
    @DisplayName("CORRUPT — routed to PlaySpecialActionUseCase with the target player, no event/outcome required")
    void handle_corrupt_playsSpecialAction() {
        var eventId = UUID.randomUUID();
        var targetPlayerId = UUID.randomUUID();
        var payload = specialActionPlayed("CORRUPT", null, null, targetPlayerId);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        then(playSpecialAction)
                .should()
                .play(new SpecialAction.Corrupt(payload.gameId(), ERA_NUMBER, ROUND_NUMBER, targetPlayerId));
    }

    @Test
    @DisplayName("unsupported specialAction — claimed but routed as a no-op")
    void handle_unsupportedSpecialAction_playsNoOp() {
        var eventId = UUID.randomUUID();
        var payload = specialActionPlayed("FORESIGHT", UUID.randomUUID(), UUID.randomUUID(), null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        then(playSpecialAction).should().play(new SpecialAction.NoOp());
    }

    @Test
    @DisplayName("SpecialActionPlayed duplicate eventId — no effect applied")
    void handle_specialActionPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                specialActionPlayed("SEAL", UUID.randomUUID(), UUID.randomUUID(), null),
                eventId,
                SPECIAL_ACTION_PLAYED_EVENT_TYPE,
                1));

        then(playSpecialAction).should(never()).play(any());
    }

    @Test
    @DisplayName("ActionRoundClosed — triggers pending CORRUPT resolution for the envelope's round")
    void handle_actionRoundClosed_resolvesPendingCorrupt() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        given(processedEvents.claim(eventId, ACTION_ROUND_CLOSED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ActionRoundClosedPayload(gameId, ERA_NUMBER, ROUND_NUMBER),
                eventId,
                ACTION_ROUND_CLOSED_EVENT_TYPE,
                1));

        then(resolvePendingCorrupt).should().resolve(gameId, ERA_NUMBER, ROUND_NUMBER);
    }

    @Test
    @DisplayName("ActionRoundClosed duplicate eventId — no resolution triggered")
    void handle_actionRoundClosedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, ACTION_ROUND_CLOSED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ActionRoundClosedPayload(UUID.randomUUID(), 1, 1), eventId, ACTION_ROUND_CLOSED_EVENT_TYPE, 1));

        then(resolvePendingCorrupt).should(never()).resolve(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("ParadoxResolutionCardPlayed — routed to PlayParadoxResolutionCardUseCase")
    void handle_paradoxResolutionCardPlayed_playsResolutionCard() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = new ParadoxResolutionCardPlayedPayload(
                gameId, ERA_NUMBER, playerId, UUID.randomUUID(), "PUSH", targetEventId, targetOutcomeId);
        given(processedEvents.claim(eventId, PARADOX_RESOLUTION_CARD_PLAYED_CONSUMER))
                .willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE, 1));

        then(playParadoxResolutionCard)
                .should()
                .play(gameId, ERA_NUMBER, playerId, "PUSH", targetEventId, targetOutcomeId);
    }

    @Test
    @DisplayName("ParadoxResolutionCardPlayed duplicate eventId — no effect applied")
    void handle_paradoxResolutionCardPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, PARADOX_RESOLUTION_CARD_PLAYED_CONSUMER))
                .willReturn(false);
        var payload = new ParadoxResolutionCardPlayedPayload(
                UUID.randomUUID(),
                ERA_NUMBER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PUSH",
                UUID.randomUUID(),
                UUID.randomUUID());

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE, 1));

        then(playParadoxResolutionCard).should(never()).play(any(), anyInt(), any(), any(), any(), any());
    }

    private static SpecialActionPlayedPayload specialActionPlayed(
            String specialAction, UUID targetEventId, UUID targetOutcomeId, UUID targetPlayerId) {
        return new SpecialActionPlayedPayload(
                UUID.randomUUID(),
                ERA_NUMBER,
                ROUND_NUMBER,
                UUID.randomUUID(),
                specialAction,
                targetEventId,
                targetOutcomeId,
                targetPlayerId);
    }

    private static CardPlayedPayload cardPlayed(
            UUID targetEventId, String cardType, UUID sourceOutcomeId, UUID targetOutcomeId) {
        return new CardPlayedPayload(
                UUID.randomUUID(),
                ERA_NUMBER,
                ROUND_NUMBER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                cardType,
                targetEventId,
                sourceOutcomeId,
                targetOutcomeId);
    }
}
