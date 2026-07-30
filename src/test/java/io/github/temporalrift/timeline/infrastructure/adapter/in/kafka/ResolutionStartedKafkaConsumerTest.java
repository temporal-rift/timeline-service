package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class ResolutionStartedKafkaConsumerTest {

    private static final String BINDING_NAME = "Sessionpublish-resolution-started-out";
    private static final String CONSUMER = "futureevent.resolution-started";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    ResolveEraUseCase resolveEra;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    ResolutionStartedKafkaConsumer consumer;

    @Test
    @DisplayName("matching binding — triggers resolution for the envelope's gameId/eraNumber")
    void handle_matchingBinding_triggersResolution() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(gameId, eraNumber), eventId, BINDING_NAME, 1));

        then(resolveEra).should().resolve(gameId, eraNumber);
    }

    @Test
    @DisplayName("unrelated binding — ignored")
    void handle_unrelatedBinding_ignored() {
        consumer.handle(
                KafkaTestMessages.withHeaders(List.of(), UUID.randomUUID(), "Sessionpublish-era-started-out", 1));

        then(resolveEra).should(never()).resolve(any(), anyInt());
    }

    @Test
    @DisplayName("duplicate eventId — no resolution triggered")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(UUID.randomUUID(), 1), eventId, BINDING_NAME, 1));

        then(resolveEra).should(never()).resolve(any(), anyInt());
    }

    @Test
    @DisplayName("unsupported version — skipped without claiming")
    void handle_unsupportedVersion_skippedWithoutClaim() {
        consumer.handle(KafkaTestMessages.withHeaders(
                new ResolutionStartedPayload(UUID.randomUUID(), 1), UUID.randomUUID(), BINDING_NAME, 99));

        then(processedEvents).should(never()).claim(any(), any());
        then(resolveEra).should(never()).resolve(any(), anyInt());
    }
}
