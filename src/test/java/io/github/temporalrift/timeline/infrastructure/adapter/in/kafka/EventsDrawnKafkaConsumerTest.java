package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class EventsDrawnKafkaConsumerTest {

    private static final String BINDING_NAME = "Sessionpublish-events-drawn-out";
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
    @DisplayName("matching binding — drafts one FutureEvent and one index row per drawn event")
    void handle_matchingBinding_draftsFutureEventsAndIndexRows() {
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
                                false),
                        new EventsDrawnPayload.FutureEvent(
                                futureEventId2,
                                "second",
                                List.of(new EventsDrawnPayload.Outcome(UUID.randomUUID(), "b", 100)),
                                false)));
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, BINDING_NAME, 1));

        then(futureEvents).should().append(eq(futureEventId1), any(FutureEventDrafted.class));
        then(futureEvents).should().append(eq(futureEventId2), any(FutureEventDrafted.class));
        then(eraIndex).should().add(futureEventId1, gameId, eraNumber, 0);
        then(eraIndex).should().add(futureEventId2, gameId, eraNumber, 1);
    }

    @Test
    @DisplayName("unrelated binding — ignored")
    void handle_unrelatedBinding_ignored() {
        consumer.handle(
                KafkaTestMessages.withHeaders(List.of(), UUID.randomUUID(), "Sessionpublish-era-started-out", 1));

        then(processedEvents).should(never()).claim(any(), any());
        then(futureEvents).should(never()).append(any(), any());
    }

    @Test
    @DisplayName("duplicate eventId — no drafting")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new EventsDrawnPayload(UUID.randomUUID(), 1, List.of()), eventId, BINDING_NAME, 1));

        then(futureEvents).should(never()).append(any(), any());
        then(eraIndex).should(never()).add(any(), any(), anyInt(), anyInt());
    }
}
