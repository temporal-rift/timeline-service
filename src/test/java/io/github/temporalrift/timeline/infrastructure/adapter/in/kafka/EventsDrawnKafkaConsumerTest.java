package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class EventsDrawnKafkaConsumerTest {

    private static final String EVENT_TYPE = "EventsDrawn";
    private static final String CONSUMER = "futureevent.events-drawn";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    EventsDrawnKafkaConsumer consumer;

    @Test
    @DisplayName("matching event type — drafts one FutureEvent and one index row per drawn event")
    void handle_matchingEventType_draftsFutureEventsAndIndexRows() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId1 = UUID.randomUUID();
        var futureEventId2 = UUID.randomUUID();
        var payload = new EventsDrawnPayload(
                gameId,
                eraNumber,
                List.of(
                        new EventsDrawnPayload.FutureEvent(
                                futureEventId1,
                                "first",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "a", 50)),
                                EventsDrawnPayload.CarryOverState.FRESH),
                        new EventsDrawnPayload.FutureEvent(
                                futureEventId2,
                                "second",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "b", 100)),
                                EventsDrawnPayload.CarryOverState.FRESH)));
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(futureEvents).should().append(eq(futureEventId1), any(FutureEventDrafted.class));
        then(futureEvents).should().append(eq(futureEventId2), any(FutureEventDrafted.class));
        then(eraIndex).should().add(futureEventId1, gameId, eraNumber, 0);
        then(eraIndex).should().add(futureEventId2, gameId, eraNumber, 1);
    }

    @Test
    @DisplayName("unrelated event type — ignored")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(List.of(), UUID.randomUUID(), "EraStarted", 1));

        then(processedEvents).should(never()).claim(any(), any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    @DisplayName("event already indexed for this era (STALL carry-over) — not re-drafted or re-indexed")
    void handle_eventAlreadyCarriedOverThisEra_skipsDraftingThatEvent() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        var carriedOverEventId = UUID.randomUUID();
        var freshEventId = UUID.randomUUID();
        var stalledEventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        given(eraIndex.findByGameIdAndEraNumber(gameId, eraNumber))
                .willReturn(List.of(new IndexedEventId(carriedOverEventId, 0)));
        var payload = new EventsDrawnPayload(
                gameId,
                eraNumber,
                List.of(
                        new EventsDrawnPayload.FutureEvent(
                                carriedOverEventId,
                                "carried",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "a", 100)),
                                EventsDrawnPayload.CarryOverState.CASCADED),
                        new EventsDrawnPayload.FutureEvent(
                                freshEventId,
                                "fresh",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "b", 100)),
                                EventsDrawnPayload.CarryOverState.FRESH),
                        new EventsDrawnPayload.FutureEvent(
                                stalledEventId,
                                "stalled",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "c", 100)),
                                EventsDrawnPayload.CarryOverState.STALLED)));

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(futureEvents).should(never()).append(eq(carriedOverEventId), any());
        then(eraIndex).should(never()).add(eq(carriedOverEventId), any(), anyInt(), anyInt());
        then(futureEvents).should().append(eq(freshEventId), any(FutureEventDrafted.class));
        then(eraIndex).should().add(freshEventId, gameId, eraNumber, 1);
        then(futureEvents).should().append(eq(stalledEventId), any(FutureEventDrafted.class));
        then(eraIndex).should().add(stalledEventId, gameId, eraNumber, 2);
    }

    @Test
    @DisplayName("duplicate eventId — no drafting")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new EventsDrawnPayload(UUID.randomUUID(), 1, List.of()), eventId, EVENT_TYPE, 1));

        then(futureEvents).should(never()).append(any(), any());
        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("payload omitting carryOverState — rejected, no event drafted")
    void handle_payloadOmittingCarryOverState_rejected() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var json = """
                {"gameId":"%s","eraNumber":1,"events":[{"eventId":"%s","title":"t","outcomes":[]}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> consumer.handle(
                        KafkaTestMessages.withHeaders(json.getBytes(StandardCharsets.UTF_8), eventId, EVENT_TYPE, 1)))
                .isInstanceOf(RuntimeException.class);

        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    @DisplayName("payload with retired isCascaded field instead of carryOverState — rejected, no event drafted")
    void handle_payloadWithRetiredIsCascadedField_rejected() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var json = """
                {"gameId":"%s","eraNumber":1,"events":[{"eventId":"%s","title":"t","outcomes":[],"isCascaded":false}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> consumer.handle(
                        KafkaTestMessages.withHeaders(json.getBytes(StandardCharsets.UTF_8), eventId, EVENT_TYPE, 1)))
                .isInstanceOf(RuntimeException.class);

        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    @DisplayName("payload with both carryOverState and the retired isCascaded field — accepted, retired field ignored")
    void handle_payloadWithBothNewAndRetiredField_accepted() {
        var eventId = UUID.randomUUID();
        var futureEventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var json = """
                {"gameId":"%s","eraNumber":1,"events":[{"eventId":"%s","title":"t","outcomes":[],
                "carryOverState":"FRESH","isCascaded":false}]}
                """.formatted(UUID.randomUUID(), futureEventId);

        consumer.handle(KafkaTestMessages.withHeaders(json.getBytes(StandardCharsets.UTF_8), eventId, EVENT_TYPE, 1));

        then(futureEvents).should().append(eq(futureEventId), any(FutureEventDrafted.class));
    }
}
