package io.github.temporalrift.timeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * Test-only collector: records every {@code timeline.events} message in arrival order, with the payload
 * eagerly parsed to a {@code Map} — a raw {@code Message<Object>} listener parameter leaves the body as
 * undeserialized {@code byte[]} (see {@code GameEventPayloads} for why), so callers can't just cast it.
 */
@TestComponent
class TimelineEventsTestCollector {

    private final ObjectMapper objectMapper;

    final List<CollectedMessage> received = new CopyOnWriteArrayList<>();

    TimelineEventsTestCollector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "timeline.events", groupId = "test-collector")
    void collect(Message<Object> message) {
        var payload = message.getPayload() instanceof byte[] bytes
                ? objectMapper.readValue(bytes, Map.class)
                : (Map<?, ?>) message.getPayload();
        var eventType = message.getHeaders().get("eventType", String.class);
        received.add(new CollectedMessage(eventType, payload));
    }

    record CollectedMessage(String eventType, Map<?, ?> payload) {}
}
