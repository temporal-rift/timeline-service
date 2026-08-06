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
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class CardPlayedAndResolutionKafkaConsumerTest {

    private static final String CARD_PLAYED_EVENT_TYPE = "CardPlayed";
    private static final String CARD_PLAYED_CONSUMER = "futureevent.card-played";
    private static final String RESOLUTION_STARTED_EVENT_TYPE = "ResolutionStarted";
    private static final String RESOLUTION_STARTED_CONSUMER = "futureevent.resolution-started";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    ApplyProbabilityShiftUseCase applyProbabilityShift;

    @Mock
    ResolveEraUseCase resolveEra;

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
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(targetEventId, "PUSH", null, targetOutcomeId), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift).should().apply(eq(targetEventId), shiftCaptor.capture());
        var shift = (ProbabilityShift.Push) shiftCaptor.getValue();
        assertThat(shift.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("SUPPRESS — applies a Suppress shift to the target event")
    void handle_suppress_appliesShift() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(targetEventId, "SUPPRESS", null, targetOutcomeId), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift).should().apply(eq(targetEventId), shiftCaptor.capture());
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
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(targetEventId, "SWING", sourceOutcomeId, targetOutcomeId),
                eventId,
                CARD_PLAYED_EVENT_TYPE,
                1));

        var shiftCaptor = ArgumentCaptor.forClass(ProbabilityShift.class);
        then(applyProbabilityShift).should().apply(eq(targetEventId), shiftCaptor.capture());
        var shift = (ProbabilityShift.Swing) shiftCaptor.getValue();
        assertThat(shift.sourceOutcomeId()).isEqualTo(sourceOutcomeId);
        assertThat(shift.targetOutcomeId()).isEqualTo(targetOutcomeId);
    }

    @Test
    @DisplayName("non-shifter card type — claims the event but never applies a shift")
    void handle_nonShifterCardType_claimsButDoesNotApply() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "AMPLIFY", null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("null card type — claims the event but never applies a shift, no NullPointerException")
    void handle_nullCardType_claimsButDoesNotApply() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), null, null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("CardPlayed duplicate eventId — no shift applied")
    void handle_cardPlayedDuplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CARD_PLAYED_CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                cardPlayed(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), eventId, CARD_PLAYED_EVENT_TYPE, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
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
        then(applyProbabilityShift).should(never()).apply(any(), any());
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
        order.verify(applyProbabilityShift).apply(eq(targetEventId), any());
        order.verify(resolveEra).resolve(eq(gameId), eq(1));
    }

    private static CardPlayedPayload cardPlayed(
            UUID targetEventId, String cardType, UUID sourceOutcomeId, UUID targetOutcomeId) {
        return new CardPlayedPayload(
                UUID.randomUUID(),
                1,
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                cardType,
                targetEventId,
                sourceOutcomeId,
                targetOutcomeId);
    }
}
