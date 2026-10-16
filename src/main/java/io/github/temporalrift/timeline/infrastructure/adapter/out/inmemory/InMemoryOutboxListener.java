package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Clock;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.infrastructure.adapter.out.outbox.OutboxMessages;

/**
 * The in-memory counterpart of the JPA outbox listener: turns the generated producer's published message into an
 * outbox row inside the producing transaction, and wakes the relay once that transaction commits.
 */
@Component
@Profile("in-memory")
class InMemoryOutboxListener {

    private final InMemoryOutboxStore store;
    private final InMemoryOutboxRelay relay;
    private final InMemoryDatabase database;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;

    InMemoryOutboxListener(
            InMemoryOutboxStore store,
            InMemoryOutboxRelay relay,
            InMemoryDatabase database,
            ObjectMapper objectMapper,
            Clock clock,
            ObservationRegistry observationRegistry) {
        this.store = store;
        this.relay = relay;
        this.database = database;
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
        store.append(
                OutboxMessages.TIMELINE_EVENTS_TOPIC, row.messageKey(), row.headers(), row.payload(), clock.instant());
        database.afterCommit(relay::signal);
    }
}
