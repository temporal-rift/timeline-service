package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

/**
 * Driven port backing Kafka consumer idempotency. Consumers claim an event for their logical consumer name before doing
 * any business work; a duplicate delivery fails the claim and is skipped.
 */
public interface ProcessedEventPort {

    /**
     * Atomically claims {@code eventId} for {@code consumer}.
     *
     * @return {@code true} if this call newly claimed the event, {@code false} if that consumer already processed it
     */
    boolean claim(UUID eventId, String consumer);
}
