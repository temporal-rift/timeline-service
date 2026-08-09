package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class EraStartedKafkaConsumerTest {

    private static final String EVENT_TYPE = "EraStarted";
    private static final String CONSUMER = "futureevent.era-started";

    @Mock
    ProcessedEventPort processedEvents;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    EraStartedKafkaConsumer consumer;

    @Test
    @DisplayName("matching event type — claims the eventId")
    void handle_matchingEventType_claimsEventId() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var payload = new EraStartedPayload(UUID.randomUUID(), 1, List.of(), List.of());

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
    }

    @Test
    @DisplayName("unrelated event type — ignored, never claims")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(List.of(), UUID.randomUUID(), "EventsDrawn", 1));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("unsupported version — skipped without claiming")
    void handle_unsupportedVersion_skippedWithoutClaim() {
        var payload = new EraStartedPayload(UUID.randomUUID(), 1, List.of(), List.of());

        consumer.handle(KafkaTestMessages.withHeaders(payload, UUID.randomUUID(), EVENT_TYPE, 2));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("duplicate eventId — claim returns false, nothing else happens")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);
        var payload = new EraStartedPayload(UUID.randomUUID(), 1, List.of(), List.of());

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
    }

    @Test
    @DisplayName("payload with retired cascadedEventIds field instead of carryOverEventIds — rejected")
    void handle_payloadWithRetiredCascadedEventIdsField_rejected() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var json = """
                {"gameId":"%s","eraNumber":1,"cascadedEventIds":[],"playerIds":[]}
                """.formatted(UUID.randomUUID());

        assertThatThrownBy(() -> consumer.handle(
                        KafkaTestMessages.withHeaders(json.getBytes(StandardCharsets.UTF_8), eventId, EVENT_TYPE, 1)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("payload omitting carryOverEventIds — rejected")
    void handle_payloadOmittingCarryOverEventIds_rejected() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var json = """
                {"gameId":"%s","eraNumber":1,"playerIds":[]}
                """.formatted(UUID.randomUUID());

        assertThatThrownBy(() -> consumer.handle(
                        KafkaTestMessages.withHeaders(json.getBytes(StandardCharsets.UTF_8), eventId, EVENT_TYPE, 1)))
                .isInstanceOf(RuntimeException.class);
    }
}
