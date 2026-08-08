package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.stream.Collectors;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code EventsDrawn} from {@code game.events}: drafts one {@code FutureEvent} per drawn event, except
 * one already carried into this era by a {@code STALL} (design.md timeline-mvp4-card-modifiers) — its aggregate
 * and era-index entry already exist from {@code ResolveEraCommandHandler}'s carry-over; drafting it again would
 * append a second {@code FutureEventDrafted} to its stream (an illegal replay state) and collide with the
 * existing era-index primary key.
 */
@Component
class EventsDrawnKafkaConsumer {

    private static final String CONSUMER = "futureevent.events-drawn";
    private static final GameEventIngestion.Spec SPEC = new GameEventIngestion.Spec("EventsDrawn", CONSUMER, 1);

    private final ProcessedEventPort processedEvents;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final ObjectMapper objectMapper;

    EventsDrawnKafkaConsumer(
            ProcessedEventPort processedEvents,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), EventsDrawnPayload.class);
            var alreadyIndexed = eraIndex.findByGameIdAndEraNumber(payload.gameId(), payload.eraNumber()).stream()
                    .map(IndexedEventId::eventId)
                    .collect(Collectors.toSet());
            var events = payload.events();
            for (int revealIndex = 0; revealIndex < events.size(); revealIndex++) {
                var futureEvent = events.get(revealIndex);
                if (alreadyIndexed.contains(futureEvent.eventId())) {
                    continue;
                }
                var outcomes = futureEvent.outcomes().stream()
                        .map(o -> new Outcome(o.outcomeId(), o.description(), o.initialProbability()))
                        .toList();
                futureEvents.append(futureEvent.eventId(), new FutureEventDrafted(futureEvent.eventId(), outcomes));
                eraIndex.add(futureEvent.eventId(), payload.gameId(), payload.eraNumber(), revealIndex);
            }
        });
    }
}
