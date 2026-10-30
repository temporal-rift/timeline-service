package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EventFence;

/**
 * The source fence of a real simulation lane: the broker's end offset of every {@code game.events} partition when the
 * open context opened. A record below that offset was already in the log before the context opened, so it belongs to
 * an earlier context or to none, even if no consumer had reached it yet.
 */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
@Profile("!in-memory")
class KafkaEventFenceAdapter implements EventFence {

    private static final String TOPIC = "game.events";
    private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(10);
    private static final String UPSERT_FENCE = """
            INSERT INTO simulation_event_fence (topic, partition_number, next_offset)
            VALUES (:topic, :partition, :nextOffset)
            ON CONFLICT (topic, partition_number) DO UPDATE
            SET next_offset = GREATEST(simulation_event_fence.next_offset, EXCLUDED.next_offset)
            """;

    private final JdbcClient jdbc;
    private final Supplier<Admin> adminFactory;
    private Admin admin;
    private final AtomicReference<Map<TopicPartition, Long>> fence = new AtomicReference<>();

    @Autowired
    KafkaEventFenceAdapter(JdbcClient jdbc, KafkaAdmin kafkaAdmin) {
        this(jdbc, () -> Admin.create(kafkaAdmin.getConfigurationProperties()));
    }

    KafkaEventFenceAdapter(JdbcClient jdbc, Supplier<Admin> adminFactory) {
        this.jdbc = jdbc;
        this.adminFactory = adminFactory;
    }

    @Override
    public boolean isFenced(String topic, int partition, long offset) {
        var current = fence.get();
        if (current == null) {
            fence.compareAndSet(null, load());
            current = fence.get();
        }
        var nextOffset = current.get(new TopicPartition(topic, partition));
        return nextOffset != null && offset < nextOffset;
    }

    @Override
    public void capture() {
        try {
            var client = client();
            var description = described(
                    client.describeTopics(List.of(TOPIC)).topicNameValues().get(TOPIC));
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            if (description != null) {
                description
                        .partitions()
                        .forEach(partition ->
                                request.put(new TopicPartition(TOPIC, partition.partition()), OffsetSpec.latest()));
            }
            if (!request.isEmpty()) {
                client.listOffsets(request)
                        .all()
                        .get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                        .forEach((partition, info) -> jdbc.sql(UPSERT_FENCE)
                                .param("topic", partition.topic())
                                .param("partition", partition.partition())
                                .param("nextOffset", info.offset())
                                .update());
            }
            fence.set(load());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording the event fence", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not record the event fence", e);
        }
    }

    /** A topic nothing was ever published to does not exist yet, and holds no record to fence. */
    private static TopicDescription described(KafkaFuture<TopicDescription> description)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return description.get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return null;
            }
            throw e;
        }
    }

    private Map<TopicPartition, Long> load() {
        var loaded = new HashMap<TopicPartition, Long>();
        jdbc.sql("SELECT topic, partition_number, next_offset FROM simulation_event_fence")
                .query((rs, row) -> Map.entry(
                        new TopicPartition(rs.getString("topic"), rs.getInt("partition_number")),
                        rs.getLong("next_offset")))
                .list()
                .forEach(entry -> loaded.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(loaded);
    }

    private synchronized Admin client() {
        if (admin == null) {
            admin = adminFactory.get();
        }
        return admin;
    }

    @PreDestroy
    synchronized void close() {
        if (admin != null) {
            admin.close(BROKER_TIMEOUT);
            admin = null;
        }
    }
}
