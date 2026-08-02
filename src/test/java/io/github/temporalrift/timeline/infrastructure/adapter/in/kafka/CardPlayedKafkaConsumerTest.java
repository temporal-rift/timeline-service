package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class CardPlayedKafkaConsumerTest {

    private static final String BINDING_NAME = "Actionpublish-card-played-out";
    private static final String CONSUMER = "futureevent.card-played";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    ApplyProbabilityShiftUseCase applyProbabilityShift;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    CardPlayedKafkaConsumer consumer;

    @Test
    @DisplayName("PUSH — applies a Push shift to the target event")
    void handle_push_appliesShift() {
        var eventId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(targetEventId, "PUSH", null, targetOutcomeId), eventId, BINDING_NAME, 1));

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
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(targetEventId, "SUPPRESS", null, targetOutcomeId), eventId, BINDING_NAME, 1));

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
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(targetEventId, "SWING", sourceOutcomeId, targetOutcomeId), eventId, BINDING_NAME, 1));

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
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(UUID.randomUUID(), "AMPLIFY", null, UUID.randomUUID()), eventId, BINDING_NAME, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("null card type — claims the event but never applies a shift, no NullPointerException")
    void handle_nullCardType_claimsButDoesNotApply() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(UUID.randomUUID(), null, null, UUID.randomUUID()), eventId, BINDING_NAME, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("unrelated binding — ignored")
    void handle_unrelatedBinding_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(
                payload(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()),
                UUID.randomUUID(),
                "Sessionpublish-era-started-out",
                1));

        then(processedEvents).should(never()).claim(any(), any());
        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    @Test
    @DisplayName("duplicate eventId — no shift applied")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);

        consumer.handle(KafkaTestMessages.withHeaders(
                payload(UUID.randomUUID(), "PUSH", null, UUID.randomUUID()), eventId, BINDING_NAME, 1));

        then(applyProbabilityShift).should(never()).apply(any(), any());
    }

    private static CardPlayedPayload payload(
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
