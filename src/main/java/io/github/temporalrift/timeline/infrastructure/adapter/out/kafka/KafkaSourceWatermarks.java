package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.GroupListing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;

/** Committed offsets of this service's consumer groups: how far each group has handled its source partitions. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class KafkaSourceWatermarks implements SourceWatermarks {

    private static final String GROUP_PREFIX = "timeline-service";
    private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<Admin> adminFactory;
    private Admin admin;

    @Autowired
    KafkaSourceWatermarks(KafkaAdmin kafkaAdmin) {
        this(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
    }

    KafkaSourceWatermarks(Supplier<Admin> adminFactory) {
        this.adminFactory = adminFactory;
    }

    @Override
    public List<SourceWatermark> current() {
        try {
            var client = client();
            var watermarks = new ArrayList<SourceWatermark>();
            var groupIds = client.listGroups().all().get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS).stream()
                    .map(GroupListing::groupId)
                    .filter(groupId -> groupId.startsWith(GROUP_PREFIX))
                    .sorted()
                    .toList();
            for (var groupId : groupIds) {
                client.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata(groupId)
                        .get(BROKER_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                        .forEach((partition, offset) -> watermarks.add(new SourceWatermark(
                                groupId, partition.topic(), partition.partition(), offset.offset())));
            }
            watermarks.sort(Comparator.comparing(SourceWatermark::groupId)
                    .thenComparing(SourceWatermark::topic)
                    .thenComparingInt(SourceWatermark::partition));
            return List.copyOf(watermarks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading consumer group offsets", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not read consumer group offsets", e);
        }
    }

    // The broker connection is opened on first use and shared, so a polling operator does not reconnect every call.
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
