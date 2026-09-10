package io.github.temporalrift.timeline;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deliberately one container pair per context, not a shared static pair. Cached contexts stay open for the
 * rest of the JVM, so two of them on one broker put two members in every consumer group — including the
 * collector's — and Kafka would hand each partition to only one of them, routing a test's own events to the
 * other context's listeners. Two of them on one database would likewise run two outbox relays over the same
 * pending rows.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("temporal_rift")
                .withUsername("temporal_rift")
                .withPassword("temporal_rift");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:3.7.0");
    }
}
