package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Captures the generated producer's {@code applicationEventPublisher.publishEvent(message)} call
 * and turns it into a durable {@code outbox_events} row, in the same transaction as the mutation that
 * triggered it ({@code phase = BEFORE_COMMIT}).
 *
 * <p>{@code timeline-service} has no Spring Modulith event-publication registry to do this for it — see
 * design.md Decision 4. {@link OutboxRelay} is the separate process that actually publishes to Kafka.
 *
 * <p>Every generated producer method stamps {@code spring.cloud.stream.sendto.destination} on the
 * message before publishing (mirroring game-service's {@code KafkaExternalizationConfig}); its presence
 * is how this listener recognizes a message meant for externalization. Unlike game-service, which
 * multiplexes several bounded contexts onto one topic, {@code timeline-service} has exactly one output
 * topic, so it is hardcoded here rather than derived per-binding.
 */
@Component
class OutboxEventListener {

    private static final String SCS_DESTINATION_HEADER = "spring.cloud.stream.sendto.destination";
    private static final String TIMELINE_EVENTS_TOPIC = "timeline.events";

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    OutboxEventListener(OutboxEventJpaRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onMessage(Message<?> message) {
        var headers = message.getHeaders();
        if (headers.get(SCS_DESTINATION_HEADER, String.class) == null) {
            return;
        }
        var gameId = headers.get("gameId", String.class);
        var headersJson = objectMapper.writeValueAsString(new LinkedHashMap<>(headers));
        var payloadJson = objectMapper.writeValueAsString(message.getPayload());
        repository.save(OutboxEventEntity.pending(
                UUID.randomUUID(), TIMELINE_EVENTS_TOPIC, gameId, headersJson, payloadJson, clock.instant()));
    }
}
