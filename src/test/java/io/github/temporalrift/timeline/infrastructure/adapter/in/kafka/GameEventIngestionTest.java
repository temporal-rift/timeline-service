package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.support.MessageBuilder;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class GameEventIngestionTest {

    @Mock
    ProcessedEventPort processedEvents;

    private SimpleMeterRegistry meterRegistry;
    private GameEventSkipMetrics skipMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        skipMetrics = new GameEventSkipMetrics(meterRegistry);
    }

    @Test
    void unknownType_isCountedWithoutClaiming() {
        var eventId = UUID.randomUUID();
        var spec = new GameEventIngestion.Spec("FactionAssigned", "membership.faction-assigned", 1, true);

        var accepted = GameEventIngestion.accept(
                KafkaTestMessages.withHeaders(new Object(), eventId, "FutureEventAdded", 1),
                spec,
                processedEvents,
                skipMetrics);

        assertThat(accepted).isEmpty();
        assertThat(counter("unknown_type")).isEqualTo(1.0);
        verify(processedEvents, never()).claim(any(), any());
    }

    @Test
    void skippedUnknownType_isProcessedExactlyOnceAfterSupportIsAdded() {
        var eventId = UUID.randomUUID();
        var skippedMessage = KafkaTestMessages.withHeaders(new Object(), eventId, "FutureEventAdded", 1);
        var unsupportedSpec = new GameEventIngestion.Spec("FactionAssigned", "membership.faction-assigned", 1, true);

        assertThat(GameEventIngestion.accept(skippedMessage, unsupportedSpec, processedEvents, skipMetrics))
                .isEmpty();
        verify(processedEvents, never()).claim(any(), any());

        var processed = new AtomicInteger();
        var supportedSpec = new GameEventIngestion.Spec("FutureEventAdded", "futureevent.added", 1);
        when(processedEvents.claim(eventId, "futureevent.added")).thenReturn(true);

        GameEventIngestion.accept(skippedMessage, supportedSpec, processedEvents, skipMetrics)
                .ifPresent(ignored -> processed.incrementAndGet());

        assertThat(processed).hasValue(1);
        verify(processedEvents).claim(eventId, "futureevent.added");
    }

    @Test
    void knownButUnhandledType_isNotCountedAsUnknown() {
        var spec = new GameEventIngestion.Spec("FactionAssigned", "membership.faction-assigned", 1, true);

        GameEventIngestion.accept(
                KafkaTestMessages.withHeaders(new Object(), UUID.randomUUID(), "GameStarted", 1),
                spec,
                processedEvents,
                skipMetrics);

        assertThat(meterRegistry.find(GameEventSkipMetrics.METRIC_NAME).counter())
                .isNull();
        verify(processedEvents, never()).claim(any(), any());
    }

    @Test
    void missingEventType_isNotCountedOrClaimed() {
        var spec = new GameEventIngestion.Spec("FactionAssigned", "membership.faction-assigned", 1, true);

        var accepted = GameEventIngestion.accept(
                MessageBuilder.withPayload(new Object()).build(), spec, processedEvents, skipMetrics);

        assertThat(accepted).isEmpty();
        assertThat(meterRegistry.find(GameEventSkipMetrics.METRIC_NAME).counter())
                .isNull();
        verify(processedEvents, never()).claim(any(), any());
    }

    @Test
    void unsupportedVersion_isCountedWithoutClaiming() {
        var eventId = UUID.randomUUID();
        var spec = new GameEventIngestion.Spec("EraStarted", "futureevent.era-started", 1);

        var accepted = GameEventIngestion.accept(
                KafkaTestMessages.withHeaders(new Object(), eventId, "EraStarted", 2),
                spec,
                processedEvents,
                skipMetrics);

        assertThat(accepted).isEmpty();
        assertThat(counter("unsupported_version")).isEqualTo(1.0);
        verify(processedEvents, never()).claim(any(), any());
    }

    @Test
    void duplicateSupportedRecord_remainsUnprocessed() {
        var eventId = UUID.randomUUID();
        var spec = new GameEventIngestion.Spec("EraStarted", "futureevent.era-started", 1);
        when(processedEvents.claim(eventId, "futureevent.era-started")).thenReturn(false);

        var accepted = GameEventIngestion.accept(
                KafkaTestMessages.withHeaders(new Object(), eventId, "EraStarted", 1),
                spec,
                processedEvents,
                skipMetrics);

        assertThat(accepted).isEmpty();
        verify(processedEvents).claim(eventId, "futureevent.era-started");
    }

    private double counter(String reason) {
        return meterRegistry
                .find(GameEventSkipMetrics.METRIC_NAME)
                .tag("reason", reason)
                .counter()
                .count();
    }
}
