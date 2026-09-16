package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EventsDrawnPayload;
import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.CascadeCarriedForwardEvent;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Consumes {@code EventsDrawn} from {@code game.events}, drafting each event unless a {@code STALL} carried it into
 * this era. A carried event already has its aggregate and era-index entry, so drafting it again would corrupt its
 * event stream and conflict with the era index; instead its per-era state (seal, annihilation, seal-breach) is
 * cleared for the new era (era-scoped-event-state capability) before anything else in this era touches it — then
 * any CASCADE armed against this era re-applies its erasure on top of that clearing (eraser-cascade-erasure
 * capability).
 */
@Component
class EventsDrawnKafkaConsumer {

    private static final String CONSUMER = "futureevent.events-drawn";
    private static final GameEventIngestion.Spec SPEC = new GameEventIngestion.Spec("EventsDrawn", CONSUMER, 1);
    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";

    private final ProcessedEventPort processedEvents;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final CascadeCarryForwardPort cascadeCarryForward;
    private final TimelineEventPublisher publisher;
    private final WeaverChainSagaUseCase weaverChainSaga;
    private final ObjectMapper objectMapper;
    private final GameEventSkipMetrics skipMetrics;
    private final Clock clock;

    EventsDrawnKafkaConsumer(
            ProcessedEventPort processedEvents,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            CascadeCarryForwardPort cascadeCarryForward,
            TimelineEventPublisher publisher,
            WeaverChainSagaUseCase weaverChainSaga,
            ObjectMapper objectMapper,
            GameEventSkipMetrics skipMetrics,
            Clock clock) {
        this.processedEvents = processedEvents;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.cascadeCarryForward = cascadeCarryForward;
        this.publisher = publisher;
        this.weaverChainSaga = weaverChainSaga;
        this.objectMapper = objectMapper;
        this.skipMetrics = skipMetrics;
        this.clock = clock;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents, skipMetrics).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), EventsDrawnPayload.class);
            var alreadyIndexed = eraIndex.findByGameIdAndEraNumber(payload.gameId(), payload.eraNumber()).stream()
                    .map(IndexedEventId::eventId)
                    .collect(Collectors.toSet());
            var events = payload.events();
            for (int revealIndex = 0; revealIndex < events.size(); revealIndex++) {
                var futureEvent = events.get(revealIndex);
                if (alreadyIndexed.contains(futureEvent.eventId())) {
                    var carried = futureEvents.findById(futureEvent.eventId());
                    futureEvents.append(futureEvent.eventId(), carried.clearEraState());
                    continue;
                }
                var outcomes = futureEvent.outcomes().stream()
                        .map(o -> new Outcome(o.outcomeId(), o.description(), o.initialProbability()))
                        .toList();
                futureEvents.append(futureEvent.eventId(), new FutureEventDrafted(futureEvent.eventId(), outcomes));
                eraIndex.add(futureEvent.eventId(), payload.gameId(), payload.eraNumber(), revealIndex);
            }
            applyPendingCascades(payload.gameId(), payload.eraNumber(), alreadyIndexed);
        });
    }

    /**
     * Applies every CASCADE confirmed for this era, after every carried event's clearing above so the carried
     * erasure lands on top of the fresh state rather than being wiped by it. A pending row whose event never
     * carried into this era (it simply resolved, so nothing to re-erase) is dropped and reported instead.
     */
    private void applyPendingCascades(UUID gameId, int eraNumber, Set<UUID> carriedEventIds) {
        for (var pending : cascadeCarryForward.findByGameAndEra(gameId, eraNumber)) {
            if (!carriedEventIds.contains(pending.eventId())) {
                cascadeCarryForward.delete(gameId, eraNumber, pending.eventId(), pending.outcomeId());
                publisher.publish(TimelineEventEnvelope.create(
                        pending.eventId(),
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        gameId,
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new SpecialRejectedEvent(
                                gameId,
                                eraNumber,
                                pending.playerId(),
                                "CASCADE",
                                null,
                                pending.eventId(),
                                pending.outcomeId(),
                                "CARRY_FORWARD_EVENT_NOT_ACTIVE"),
                        clock));
                continue;
            }
            var futureEvent = futureEvents.findById(pending.eventId());
            futureEvents.append(pending.eventId(), futureEvent.annihilateOutcome(pending.outcomeId()));
            weaverChainSaga.annihilateOutcome(gameId, eraNumber, pending.eventId(), pending.outcomeId());
            cascadeCarryForward.delete(gameId, eraNumber, pending.eventId(), pending.outcomeId());
            publisher.publish(TimelineEventEnvelope.create(
                    pending.eventId(),
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new CascadeCarriedForwardEvent(gameId, eraNumber, pending.eventId(), pending.outcomeId()),
                    clock));
        }
    }
}
