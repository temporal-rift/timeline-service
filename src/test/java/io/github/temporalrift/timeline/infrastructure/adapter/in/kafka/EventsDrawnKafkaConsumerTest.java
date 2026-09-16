package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.CarryOverState;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EventsDrawnFutureEvent;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EventsDrawnOutcome;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EventsDrawnPayload;
import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.CascadeCarriedForwardEvent;
import io.github.temporalrift.timeline.domain.event.EraStateCleared;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort.CascadeCarryForward;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

@ExtendWith(MockitoExtension.class)
class EventsDrawnKafkaConsumerTest {

    private static final String EVENT_TYPE = "EventsDrawn";
    private static final String CONSUMER = "futureevent.events-drawn";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    GameEventSkipMetrics skipMetrics;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    CascadeCarryForwardPort cascadeCarryForward;

    @Mock
    TimelineEventPublisher publisher;

    @Mock
    WeaverChainSagaUseCase weaverChainSaga;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC);

    private EventsDrawnKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EventsDrawnKafkaConsumer(
                processedEvents,
                futureEvents,
                eraIndex,
                cascadeCarryForward,
                publisher,
                weaverChainSaga,
                objectMapper,
                skipMetrics,
                clock);
    }

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
                        new EventsDrawnFutureEvent(
                                futureEventId1,
                                "first",
                                List.of(new EventsDrawnOutcome(UUID.randomUUID(), "a", 50)),
                                CarryOverState.FRESH),
                        new EventsDrawnFutureEvent(
                                futureEventId2,
                                "second",
                                List.of(new EventsDrawnOutcome(UUID.randomUUID(), "b", 100)),
                                CarryOverState.FRESH)));
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
    @DisplayName("event already indexed for this era (STALL carry-over) — not re-drafted or re-indexed, "
            + "but its per-era state is cleared")
    void handle_eventAlreadyCarriedOverThisEra_skipsDraftingThatEvent() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        var carriedOverEventId = UUID.randomUUID();
        var freshEventId = UUID.randomUUID();
        var stalledEventId = UUID.randomUUID();
        var carriedOutcome = new Outcome(UUID.randomUUID(), "a", 100, true, false);
        var carried = FutureEvent.replay(
                carriedOverEventId, List.of(new FutureEventDrafted(carriedOverEventId, List.of(carriedOutcome))));
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        given(eraIndex.findByGameIdAndEraNumber(gameId, eraNumber))
                .willReturn(List.of(new IndexedEventId(carriedOverEventId, 0)));
        given(futureEvents.findById(carriedOverEventId)).willReturn(carried);
        var payload = new EventsDrawnPayload(
                gameId,
                eraNumber,
                List.of(
                        new EventsDrawnFutureEvent(
                                carriedOverEventId,
                                "carried",
                                List.of(new EventsDrawnOutcome(UUID.randomUUID(), "a", 100)),
                                CarryOverState.CASCADED),
                        new EventsDrawnFutureEvent(
                                freshEventId,
                                "fresh",
                                List.of(new EventsDrawnOutcome(UUID.randomUUID(), "b", 100)),
                                CarryOverState.FRESH),
                        new EventsDrawnFutureEvent(
                                stalledEventId,
                                "stalled",
                                List.of(new EventsDrawnOutcome(UUID.randomUUID(), "c", 100)),
                                CarryOverState.STALLED)));

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(futureEvents).should(never()).append(eq(carriedOverEventId), any(FutureEventDrafted.class));
        then(futureEvents).should().append(eq(carriedOverEventId), any(EraStateCleared.class));
        then(eraIndex).should(never()).add(eq(carriedOverEventId), any(), anyInt(), anyInt());
        then(futureEvents).should().append(eq(freshEventId), any(FutureEventDrafted.class));
        then(eraIndex).should().add(freshEventId, gameId, eraNumber, 1);
        then(futureEvents).should().append(eq(stalledEventId), any(FutureEventDrafted.class));
        then(eraIndex).should().add(stalledEventId, gameId, eraNumber, 2);
    }

    @Test
    @DisplayName("confirmed CASCADE pending for this era, whose target carried in — erases the outcome and "
            + "publishes CascadeCarriedForward")
    void handle_confirmedCascadePendingForCarriedEvent_erasesOutcomeAndPublishes() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        var carriedOverEventId = UUID.randomUUID();
        var erasedOutcomeId = UUID.randomUUID();
        var player = UUID.randomUUID();
        var carried = FutureEvent.replay(
                carriedOverEventId,
                List.of(new FutureEventDrafted(carriedOverEventId, List.of(new Outcome(erasedOutcomeId, "a", 100)))));
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        given(eraIndex.findByGameIdAndEraNumber(gameId, eraNumber))
                .willReturn(List.of(new IndexedEventId(carriedOverEventId, 0)));
        given(futureEvents.findById(carriedOverEventId)).willReturn(carried);
        given(cascadeCarryForward.findByGameAndEra(gameId, eraNumber))
                .willReturn(List.of(new CascadeCarryForward(player, carriedOverEventId, erasedOutcomeId)));
        var payload = new EventsDrawnPayload(
                gameId,
                eraNumber,
                List.of(new EventsDrawnFutureEvent(
                        carriedOverEventId,
                        "carried",
                        List.of(new EventsDrawnOutcome(erasedOutcomeId, "a", 100)),
                        CarryOverState.CASCADED)));

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        assertThat(carried.outcomes())
                .filteredOn(o -> o.outcomeId().equals(erasedOutcomeId))
                .allSatisfy(o -> assertThat(o.annihilated()).isTrue());
        then(cascadeCarryForward).should().delete(gameId, eraNumber, carriedOverEventId, erasedOutcomeId);
        then(weaverChainSaga).should().annihilateOutcome(gameId, eraNumber, carriedOverEventId, erasedOutcomeId);
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        assertThat(captor.getValue().payload()).isInstanceOf(CascadeCarriedForwardEvent.class);
    }

    @Test
    @DisplayName("CASCADE pending for an event that did not carry into this era — rejected and reported")
    void handle_cascadePendingForEventNotCarriedIn_rejectedAndReported() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var eraNumber = 2;
        var targetEventId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var player = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        given(eraIndex.findByGameIdAndEraNumber(gameId, eraNumber)).willReturn(List.of());
        given(cascadeCarryForward.findByGameAndEra(gameId, eraNumber))
                .willReturn(List.of(new CascadeCarryForward(player, targetEventId, targetOutcomeId)));
        var payload = new EventsDrawnPayload(gameId, eraNumber, List.of());

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(cascadeCarryForward).should().delete(gameId, eraNumber, targetEventId, targetOutcomeId);
        then(futureEvents).should(never()).findById(targetEventId);
        then(weaverChainSaga).should(never()).annihilateOutcome(any(), anyInt(), any(), any());
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var rejected = (SpecialRejectedEvent) captor.getValue().payload();
        assertThat(rejected.specialAction()).isEqualTo("CASCADE");
        assertThat(rejected.targetEventId()).isEqualTo(targetEventId);
        assertThat(rejected.targetOutcomeId()).isEqualTo(targetOutcomeId);
        assertThat(rejected.reason()).isEqualTo("CARRY_FORWARD_EVENT_NOT_ACTIVE");
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
}
