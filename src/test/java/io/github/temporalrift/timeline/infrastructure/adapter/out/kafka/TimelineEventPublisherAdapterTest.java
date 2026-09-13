package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;

import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.EraResolutionCompletedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.EraTerminalResolution;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;

@ExtendWith(MockitoExtension.class)
class TimelineEventPublisherAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    @Mock
    TimelineEventWireMapper mapper;

    TimelineEventPublisherAdapter adapter;

    @BeforeEach
    void setUp() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            adapter = new TimelineEventPublisherAdapter(applicationEventPublisher, mapper, factory.getValidator());
        }
    }

    @Test
    void publish_validPayload_publishesMessage() {
        var gameId = UUID.randomUUID();
        var domain = new EraResolutionCompleted(
                gameId,
                1,
                List.of(new TerminalResolution(
                        UUID.randomUUID(), 0, TerminalResolution.TerminalState.OUTCOME_APPLIED, UUID.randomUUID())));
        var wire = new EraResolutionCompletedPayload(
                gameId,
                1,
                List.of(new EraTerminalResolution(UUID.randomUUID(), 0, "OUTCOME_APPLIED", UUID.randomUUID())));
        given(mapper.toWire(domain)).willReturn(wire);
        var envelope = TimelineEventEnvelope.create(UUID.randomUUID(), "FutureEvent", gameId, 1, domain, CLOCK);

        adapter.publish(envelope);

        then(applicationEventPublisher).should().publishEvent(any(Message.class));
    }

    @Test
    void publish_emptyTerminalResolutions_rejectedBeforeOutbox() {
        var gameId = UUID.randomUUID();
        var domain = new EraResolutionCompleted(gameId, 1, List.of());
        var wire = new EraResolutionCompletedPayload(gameId, 1, List.of());
        given(mapper.toWire(domain)).willReturn(wire);
        var envelope = TimelineEventEnvelope.create(UUID.randomUUID(), "FutureEvent", gameId, 1, domain, CLOCK);

        assertThatThrownBy(() -> adapter.publish(envelope))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("EraResolutionCompleted")
                .hasMessageContaining("terminalResolutions");

        then(applicationEventPublisher).should(never()).publishEvent(any(Message.class));
    }

    @Test
    void publish_nullGameId_rejectedWithoutExposingEventData() {
        var domain = new EraResolutionCompleted(
                UUID.randomUUID(),
                1,
                List.of(new TerminalResolution(UUID.randomUUID(), 0, TerminalResolution.TerminalState.CASCADED, null)));
        var wire = new EraResolutionCompletedPayload(null, 1, List.of());
        given(mapper.toWire(domain)).willReturn(wire);
        var envelope =
                TimelineEventEnvelope.create(UUID.randomUUID(), "FutureEvent", UUID.randomUUID(), 1, domain, CLOCK);

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ConstraintViolationException.class, () -> adapter.publish(envelope));

        assertThat(thrown.getMessage()).contains("gameId");
        assertThat(thrown.getMessage()).doesNotContain("terminalResolutions=[]");
        then(applicationEventPublisher).should(never()).publishEvent(any(Message.class));
    }
}
