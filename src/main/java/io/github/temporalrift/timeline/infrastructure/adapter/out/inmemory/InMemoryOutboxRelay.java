package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory.InMemoryOutboxStore.OutboxRow;
import io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory.InMemoryOutboxStore.State;

/**
 * Relays committed outbox rows to the host's {@code simulationEventSink} in outbox order, on one thread of its own so a
 * sink that re-enters the application can never run inside — or hold up — a producing transaction. A failing sink
 * leaves its row pending and stops the sweep there, so a later row never overtakes it; the sweep is retried shortly.
 */
@Component
@Profile("in-memory")
class InMemoryOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOutboxRelay.class);
    private static final long RETRY_DELAY_MILLIS = 1_000;
    private static final String TOPIC = "topic";
    private static final String KEY = "key";
    private static final String HEADERS = "headers";
    private static final String PAYLOAD = "payload";
    // Spring generates these two on every message; they are never mapped onto a Kafka record.
    private static final Set<String> UNMAPPED_HEADERS = Set.of("id", "timestamp");

    private final InMemoryOutboxStore store;
    private final Consumer<Map<String, Object>> sink;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "in-memory-outbox-relay");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean sweepQueued = new AtomicBoolean();

    InMemoryOutboxRelay(
            InMemoryOutboxStore store,
            @Qualifier("simulationEventSink") Consumer<Map<String, Object>> sink,
            ObjectMapper objectMapper) {
        this.store = store;
        this.sink = sink;
        this.objectMapper = objectMapper;
    }

    /** Asks for a sweep; calls made while one is already queued are folded into it. */
    void signal() {
        if (sweepQueued.compareAndSet(false, true)) {
            schedule(0);
        }
    }

    private void schedule(long delayMillis) {
        try {
            executor.schedule(this::sweep, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException _) {
            sweepQueued.set(false); // shut down with the application context
        }
    }

    private void sweep() {
        sweepQueued.set(false);
        for (var row : store.pending()) {
            if (store.transition(row.seq(), State.PENDING, State.SENDING)) {
                if (send(row)) {
                    store.transition(row.seq(), State.SENDING, State.SENT);
                } else {
                    store.transition(row.seq(), State.SENDING, State.PENDING);
                    if (sweepQueued.compareAndSet(false, true)) {
                        schedule(RETRY_DELAY_MILLIS);
                    }
                    return;
                }
            }
        }
    }

    private boolean send(OutboxRow row) {
        try {
            sink.accept(toRecord(row));
            return true;
        } catch (Exception e) {
            log.warn("Failed to relay outbox event {} to the simulation sink — will retry", row.id(), e);
            return false;
        }
    }

    /** The record the Kafka relay would send: stored headers minus the unmapped ones, and the payload as JSON bytes. */
    private Map<String, Object> toRecord(OutboxRow row) {
        Map<?, ?> stored = objectMapper.readValue(row.headers(), Map.class);
        var headers = new LinkedHashMap<String, String>();
        stored.forEach((name, value) -> {
            if (value != null && !UNMAPPED_HEADERS.contains(String.valueOf(name))) {
                headers.put(String.valueOf(name), String.valueOf(value));
            }
        });
        var payload = objectMapper.writeValueAsBytes(objectMapper.readValue(row.payload(), Object.class));
        var record = new LinkedHashMap<String, Object>();
        record.put(TOPIC, row.topic());
        record.put(KEY, row.messageKey());
        record.put(HEADERS, Collections.unmodifiableMap(headers));
        record.put(PAYLOAD, payload);
        return record;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
