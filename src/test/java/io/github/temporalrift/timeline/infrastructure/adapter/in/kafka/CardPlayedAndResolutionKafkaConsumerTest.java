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

import io.github.temporalrift.timeline.application.port.in.ApplyMomentumBonusUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

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
    private static final String ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE = "ActivistDeclarationRecorded";
    private static final String ACTIVIST_DECLARATION_RECORDED_CONSUMER = "futureevent.activist-declaration-recorded";
    private static final int ERA_NUMBER = 2;
    private static final int ROUND_NUMBER = 3;

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    RoundActionBufferPort buffer;

    @Mock
    ReplayRoundActionsUseCase replayRoundActions;

    @Mock
    ResolveEraUseCase resolveEra;

    @Mock
    PlayParadoxResolutionCardUseCase playParadoxResolutionCard;

    @Mock
    ApplyMomentumBonusUseCase applyMomentumBonus;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    CardPlayedAndResolutionKafkaConsumer consumer;

    @Test
    @DisplayName("PUSH — buffered with its source/target outcomes, not applied directly")
    void handle_push_buffersAction() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "PUSH", null, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), actionCaptor.capture());
        var action = actionCaptor.getValue();
        assertThat(action.kind()).isEqualTo(ActionKind.CARD_PLAYED);
        assertThat(action.cardType()).isEqualTo("PUSH");
        assertThat(action.targetEventId()).isEqualTo(targetEventId);
        assertThat(action.targetOutcomeId()).isEqualTo(targetOutcomeId);
        assertThat(action.playerId()).isEqualTo(payload.playerId());
        assertThat(action.envelopeEventId()).isEqualTo(eventId);
        then(replayRoundActions).should(never()).replay(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("SWING — buffered with both source and target outcomes")
    void handle_swing_buffersBothOutcomes() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var sourceOutcomeId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = cardPlayed(targetEventId, "SWING", sourceOutcomeId, targetOutcomeId);
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), actionCaptor.capture());
        assertThat(actionCaptor.getValue().sourceOutcomeId()).isEqualTo(sourceOutcomeId);
        assertThat(actionCaptor.getValue().targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("every known CardPlayed type is buffered, not just shifters")
    void handle_everyKnownCardType_buffersAction() {
        for (var cardType : new String[] {
            "AMPLIFY", "NULLIFY", "REDIRECT", "STALL", "COLLIDE", "INTERCEPT", "SCAN", "TRACE", "DECOY", "JAM"
        }) {
            var eventId = UUID.randomUUID();
            var payload = cardPlayed(UUID.randomUUID(), cardType, UUID.randomUUID(), UUID.randomUUID());
            given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

            consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, CARD_PLAYED_EVENT_TYPE, 1));

            then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), any());
        }
    }

    @Test
    @DisplayName("null card type — claims the event but buffers nothing, no NullPointerException")
    void handle_nullCardType_claimsButBuffersNothing() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), null, null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("CardPlayed duplicate eventId — nothing buffered")
    void handle_cardPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
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
    @DisplayName("unrelated event type — ignored, nothing buffered or resolved")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), UUID.randomUUID(), "EraStarted", 1));

        then(processedEvents).should(never()).claim(any(), any());
        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
        then(resolveEra).should(never()).resolve(any(), anyInt());
    }

    @Test
    @DisplayName("CardPlayed then ResolutionStarted on the same consumer instance apply in that order")
    void handle_cardPlayedThenResolutionStarted_buffersBeforeResolving() {
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
        // there is no second thread/group that could observe pre-buffer state.
        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(targetEventId, "PUSH", null, targetOutcomeId), cardEventId, CARD_PLAYED_EVENT_TYPE, 1));
        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(gameId, 1), resolutionEventId, RESOLUTION_STARTED_EVENT_TYPE, 1));

        var order = inOrder(buffer, resolveEra);
        order.verify(buffer).save(any(), anyInt(), anyInt(), any());
        order.verify(resolveEra).resolve(eq(gameId), eq(1));
    }

    @Test
    @DisplayName("SEAL — buffered as a SpecialActionPlayed action")
    void handle_seal_buffersSpecialAction() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = specialActionPlayed("SEAL", targetEventId, targetOutcomeId, null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), actionCaptor.capture());
        var action = actionCaptor.getValue();
        assertThat(action.kind()).isEqualTo(ActionKind.SPECIAL_ACTION_PLAYED);
        assertThat(action.specialAction()).isEqualTo("SEAL");
        assertThat(action.targetEventId()).isEqualTo(targetEventId);
        assertThat(action.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("CORRUPT — buffered with the target player, no event/outcome required")
    void handle_corrupt_buffersTargetPlayer() {
        var eventId = UUID.randomUUID();
        var targetPlayerId = UUID.randomUUID();
        var payload = specialActionPlayed("CORRUPT", null, null, targetPlayerId);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), actionCaptor.capture());
        assertThat(actionCaptor.getValue().targetPlayerId()).isEqualTo(targetPlayerId);
    }

    @Test
    @DisplayName("MIMIC — buffered as a SpecialActionPlayed action, event-targeting like SEAL")
    void handle_mimic_buffersSpecialAction() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = specialActionPlayed("MIMIC", targetEventId, targetOutcomeId, null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(ROUND_NUMBER), actionCaptor.capture());
        var action = actionCaptor.getValue();
        assertThat(action.kind()).isEqualTo(ActionKind.SPECIAL_ACTION_PLAYED);
        assertThat(action.specialAction()).isEqualTo("MIMIC");
        assertThat(action.targetEventId()).isEqualTo(targetEventId);
        assertThat(action.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("permanent no-op specialActions — claimed but buffer nothing (faction-specials capability)")
    void handle_permanentNoOpSpecialActions_buffersNothing() {
        // FORESIGHT/FULFILLMENT/REWRITE/OBSCURE/EXPOSE have no probability effect by design; RALLY/MOMENTUM
        // are handled only via the separate ActivistDeclarationRecorded consumer, never via this event.
        for (var specialAction :
                new String[] {"FORESIGHT", "FULFILLMENT", "REWRITE", "OBSCURE", "EXPOSE", "RALLY", "MOMENTUM"}) {
            var eventId = UUID.randomUUID();
            var payload = specialActionPlayed(specialAction, UUID.randomUUID(), UUID.randomUUID(), null);
            given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER))
                    .willReturn(true);

            consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

            then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
        }
    }

    @Test
    @DisplayName("unsupported specialAction — claimed but buffers nothing")
    void handle_unsupportedSpecialAction_buffersNothing() {
        var eventId = UUID.randomUUID();
        var payload = specialActionPlayed("CASCADE", UUID.randomUUID(), UUID.randomUUID(), null);
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, SPECIAL_ACTION_PLAYED_EVENT_TYPE, 1));

        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("SpecialActionPlayed duplicate eventId — nothing buffered")
    void handle_specialActionPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, SPECIAL_ACTION_PLAYED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                specialActionPlayed("SEAL", UUID.randomUUID(), UUID.randomUUID(), null),
                eventId,
                SPECIAL_ACTION_PLAYED_EVENT_TYPE,
                1));

        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("ActionRoundClosed — triggers the round's replay")
    void handle_actionRoundClosed_triggersReplay() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        given(processedEvents.claim(eventId, ACTION_ROUND_CLOSED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ActionRoundClosedPayload(gameId, ERA_NUMBER, ROUND_NUMBER),
                eventId,
                ACTION_ROUND_CLOSED_EVENT_TYPE,
                1));

        then(replayRoundActions).should().replay(gameId, ERA_NUMBER, ROUND_NUMBER);
    }

    @Test
    @DisplayName("ActionRoundClosed duplicate eventId — no replay triggered")
    void handle_actionRoundClosedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, ACTION_ROUND_CLOSED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ActionRoundClosedPayload(UUID.randomUUID(), 1, 1), eventId, ACTION_ROUND_CLOSED_EVENT_TYPE, 1));

        then(replayRoundActions).should(never()).replay(any(), anyInt(), anyInt());
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

    @Test
    @DisplayName("ActivistDeclarationRecorded MOMENTUM — applies the bonus immediately, nothing buffered")
    void handle_activistDeclarationMomentum_appliesBonusImmediately() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = activistDeclarationRecorded("MOMENTUM", targetEventId, targetOutcomeId);
        given(processedEvents.claim(eventId, ACTIVIST_DECLARATION_RECORDED_CONSUMER))
                .willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE, 1));

        then(applyMomentumBonus).should().apply(targetEventId, targetOutcomeId);
        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("ActivistDeclarationRecorded RALLY — buffered into round 1, bonus never applied")
    void handle_activistDeclarationRally_buffersIntoRoundOne() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var payload = activistDeclarationRecorded("RALLY", targetEventId, targetOutcomeId);
        given(processedEvents.claim(eventId, ACTIVIST_DECLARATION_RECORDED_CONSUMER))
                .willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE, 1));

        var actionCaptor = ArgumentCaptor.forClass(BufferedAction.class);
        then(buffer).should().save(eq(payload.gameId()), eq(ERA_NUMBER), eq(1), actionCaptor.capture());
        var action = actionCaptor.getValue();
        assertThat(action.kind()).isEqualTo(ActionKind.SPECIAL_ACTION_PLAYED);
        assertThat(action.specialAction()).isEqualTo("RALLY");
        assertThat(action.targetEventId()).isEqualTo(targetEventId);
        assertThat(action.targetOutcomeId()).isEqualTo(targetOutcomeId);
        then(applyMomentumBonus).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("ActivistDeclarationRecorded duplicate eventId — nothing buffered or applied")
    void handle_activistDeclarationDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, ACTIVIST_DECLARATION_RECORDED_CONSUMER))
                .willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                activistDeclarationRecorded("RALLY", UUID.randomUUID(), UUID.randomUUID()),
                eventId,
                ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE,
                1));

        then(buffer).should(never()).save(any(), anyInt(), anyInt(), any());
        then(applyMomentumBonus).should(never()).apply(any(), any());
    }

    private static ActivistDeclarationRecordedPayload activistDeclarationRecorded(
            String mode, UUID targetEventId, UUID targetOutcomeId) {
        return new ActivistDeclarationRecordedPayload(
                UUID.randomUUID(), ERA_NUMBER, 1, UUID.randomUUID(), mode, targetEventId, targetOutcomeId);
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
