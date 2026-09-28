package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Preserves causal order for timeline lifecycle records on each game partition. Each proxied handler
 * commits its record transaction before the next record is dispatched, so actions cannot overtake
 * drafting, resolution cannot overtake replay, and cleanup cannot overtake the effects it removes.
 */
@Component
class TimelineGameEventsKafkaConsumer {

    private final EraStartedGameEventHandler eraStarted;
    private final EventsDrawnGameEventHandler eventsDrawn;
    private final RoundResolutionGameEventHandler roundResolution;

    TimelineGameEventsKafkaConsumer(
            EraStartedGameEventHandler eraStarted,
            EventsDrawnGameEventHandler eventsDrawn,
            RoundResolutionGameEventHandler roundResolution) {
        this.eraStarted = eraStarted;
        this.eventsDrawn = eventsDrawn;
        this.roundResolution = roundResolution;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service.futureevent.game-events")
    public void handle(Message<Object> message) {
        switch (message.getHeaders().get("eventType", String.class)) {
            case "EraStarted" -> eraStarted.handle(message);
            case "EventsDrawn" -> eventsDrawn.handle(message);
            case null, default -> roundResolution.handle(message);
        }
    }
}
