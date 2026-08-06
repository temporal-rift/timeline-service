package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class EraStartedKafkaConsumerTest {

    private static final String EVENT_TYPE = "EraStarted";
    private static final String CONSUMER = "futureevent.era-started";

    @Mock
    ProcessedEventPort processedEvents;

    @InjectMocks
    EraStartedKafkaConsumer consumer;

    @Test
    @DisplayName("matching event type — claims the eventId")
    void handle_matchingEventType_claimsEventId() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(Map.of(), eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
    }

    @Test
    @DisplayName("unrelated event type — ignored, never claims")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(Map.of(), UUID.randomUUID(), "EventsDrawn", 1));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("unsupported version — skipped without claiming")
    void handle_unsupportedVersion_skippedWithoutClaim() {
        consumer.handle(KafkaTestMessages.withHeaders(Map.of(), UUID.randomUUID(), EVENT_TYPE, 2));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("duplicate eventId — claim returns false, nothing else happens")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(Map.of(), eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
    }
}
