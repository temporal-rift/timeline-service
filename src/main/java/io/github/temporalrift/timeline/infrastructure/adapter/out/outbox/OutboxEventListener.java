package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.time.Clock;
import java.util.UUID;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Profile;
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
 * <p>{@code timeline-service} has no Spring Modulith event-publication registry to do this for it.
 * {@link OutboxRelay} is the separate process that actually publishes to Kafka.
 *
 * <p>Every generated producer method stamps {@code spring.cloud.stream.sendto.destination} on the
 * message before publishing (mirroring game-service's {@code KafkaExternalizationConfig}); its presence
 * is how this listener recognizes a message meant for externalization. Unlike game-service, which
 * multiplexes several bounded contexts onto one topic, {@code timeline-service} has exactly one output
 * topic, so it is hardcoded here rather than derived per-binding.
 */
@Component
@Profile("!in-memory")
class OutboxEventListener {

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;

    OutboxEventListener(
            OutboxEventJpaRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            ObservationRegistry observationRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.observationRegistry = observationRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onMessage(Message<?> message) {
        if (!OutboxMessages.isExternalized(message)) {
            return;
        }
        Observation.createNotStarted("timeline.outbox.event", observationRegistry)
                .contextualName("persist timeline outbox event")
                .observe(() -> persist(message));
    }

    private void persist(Message<?> message) {
        var row = OutboxMessages.serialize(objectMapper, message);
        repository.save(OutboxEventEntity.pending(
                UUID.randomUUID(),
                OutboxMessages.TIMELINE_EVENTS_TOPIC,
                row.messageKey(),
                row.headers(),
                row.payload(),
                clock.instant()));
    }
}
