package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelineGameEventsKafkaConsumerTest {

    @Mock
    EraStartedGameEventHandler eraStarted;

    @Mock
    EventsDrawnGameEventHandler eventsDrawn;

    @Mock
    RoundResolutionGameEventHandler roundResolution;

    @InjectMocks
    TimelineGameEventsKafkaConsumer consumer;

    @ParameterizedTest
    @ValueSource(strings = {"EraStarted", "EventsDrawn"})
    void handle_initializationRecord_routesOnlyToItsHandler(String eventType) {
        var message = KafkaTestMessages.withHeaders("payload", UUID.randomUUID(), eventType, 1);

        consumer.handle(message);

        if ("EraStarted".equals(eventType)) {
            verify(eraStarted).handle(message);
            verifyNoInteractions(eventsDrawn, roundResolution);
        } else {
            verify(eventsDrawn).handle(message);
            verifyNoInteractions(eraStarted, roundResolution);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "CardPlayed", "SpecialActionPlayed", "ActionRoundClosed", "ResolutionStarted",
                "ParadoxResolutionCardPlayed", "ActivistDeclarationRecorded", "EraEnded", "GameEnded",
                "UnknownEvent", "FactionAssigned"
            })
    void handle_otherRecord_preservesRoundHandlerValidationAndSkipMetrics(String eventType) {
        var message = KafkaTestMessages.withHeaders("payload", UUID.randomUUID(), eventType, 1);

        consumer.handle(message);

        verify(roundResolution).handle(message);
        verifyNoInteractions(eraStarted, eventsDrawn);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EraStarted", "EventsDrawn", "ActionRoundClosed"})
    void handle_failedRecord_propagatesToKafkaRetryAndDeadLetterPolicy(String eventType) {
        var message = KafkaTestMessages.withHeaders("payload", UUID.randomUUID(), eventType, 1);
        var failure = new IllegalStateException("record failed");
        switch (eventType) {
            case "EraStarted" -> doThrow(failure).when(eraStarted).handle(message);
            case "EventsDrawn" -> doThrow(failure).when(eventsDrawn).handle(message);
            default -> doThrow(failure).when(roundResolution).handle(message);
        }

        assertThatThrownBy(() -> consumer.handle(message)).isSameAs(failure);
    }
}
