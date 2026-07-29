package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TimelineServiceApplicationIT {

    @Autowired
    EventStorePort eventStore;

    @Autowired
    ProcessedEventPort processedEvents;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void eventStoreSchemaAndAdapterWork() {
        var aggregateId = UUID.randomUUID();
        var event = new StoredEvent(
                UUID.randomUUID(), aggregateId, "FutureEvent", "SmokeTest", 1, "{\"ok\":true}", Instant.now(), 0L);

        eventStore.append(event);

        // Assert the stable identity fields rather than full equality: Postgres jsonb reformats the payload
        // ({"ok":true} -> {"ok": true}) and timestamptz truncates nanos, so a round-tripped record never equals
        // byte-for-byte.
        assertThat(eventStore.readStream(aggregateId)).singleElement().satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(event.id());
            assertThat(stored.aggregateId()).isEqualTo(aggregateId);
            assertThat(stored.aggregateType()).isEqualTo("FutureEvent");
            assertThat(stored.eventType()).isEqualTo("SmokeTest");
            assertThat(stored.sequenceNr()).isZero();
        });
    }

    @Test
    void processedEventClaimIsIdempotent() {
        var eventId = UUID.randomUUID();

        assertThat(processedEvents.claim(eventId, "it-smoke")).isTrue();
        assertThat(processedEvents.claim(eventId, "it-smoke")).isFalse();
    }

    @Test
    void kafkaBrokerIsReachable() throws Exception {
        kafkaTemplate.send("timeline.it-smoke", "k", "v").get(10, TimeUnit.SECONDS);
    }
}
