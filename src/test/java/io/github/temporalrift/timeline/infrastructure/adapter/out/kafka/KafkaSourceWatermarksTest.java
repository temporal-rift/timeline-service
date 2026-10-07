package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListGroupsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;

class KafkaSourceWatermarksTest {

    private final Admin admin = mock(Admin.class);

    private void groups(String... ids) {
        var listing = mock(ListGroupsResult.class);
        Collection<GroupListing> groups = Arrays.stream(ids)
                .map(id -> new GroupListing(id, Optional.empty(), "consumer", Optional.empty()))
                .toList();
        given(listing.all()).willReturn(KafkaFuture.completedFuture(groups));
        given(admin.listGroups()).willReturn(listing);
    }

    private void offsets(String groupId, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> committed) {
        var result = mock(ListConsumerGroupOffsetsResult.class);
        given(result.partitionsToOffsetAndMetadata(groupId)).willReturn(committed);
        given(admin.listConsumerGroupOffsets(groupId)).willReturn(result);
    }

    @Test
    @DisplayName("only this service's groups are reported, ordered by group, topic and partition")
    void current_reportsOwnGroupsInOrder() {
        groups(
                "timeline-service.futureevent.faction-assigned",
                "other-service",
                "timeline-service.futureevent.game-events");
        offsets(
                "timeline-service.futureevent.faction-assigned",
                KafkaFuture.completedFuture(Map.of(new TopicPartition("game.events", 1), new OffsetAndMetadata(9))));
        offsets(
                "timeline-service.futureevent.game-events",
                KafkaFuture.completedFuture(Map.of(
                        new TopicPartition("game.events", 1), new OffsetAndMetadata(4),
                        new TopicPartition("game.events", 0), new OffsetAndMetadata(7))));

        var watermarks = new KafkaSourceWatermarks(() -> admin).current();

        assertThat(watermarks)
                .containsExactly(
                        new SourceWatermark("timeline-service.futureevent.faction-assigned", "game.events", 1, 9),
                        new SourceWatermark("timeline-service.futureevent.game-events", "game.events", 0, 7),
                        new SourceWatermark("timeline-service.futureevent.game-events", "game.events", 1, 4));
    }

    @Test
    @DisplayName("one admin client is created on first use, reused by every call and closed on shutdown")
    void current_reusesOneClientAndClosesIt() {
        groups();
        var created = new AtomicInteger();
        var watermarks = new KafkaSourceWatermarks(() -> {
            created.incrementAndGet();
            return admin;
        });

        watermarks.current();
        watermarks.current();
        watermarks.close();
        watermarks.close();

        assertThat(created).hasValue(1);
        verify(admin, times(1)).close(any(Duration.class));
    }

    @Test
    @DisplayName("a broker failure surfaces as an error rather than a partial answer")
    void current_brokerFailure_isAnError() throws Exception {
        groups("timeline-service.futureevent.faction-assigned");
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> failed = mock(KafkaFuture.class);
        given(failed.get(anyLong(), any(TimeUnit.class)))
                .willThrow(new ExecutionException(new TimeoutException("broker unreachable")));
        offsets("timeline-service.futureevent.faction-assigned", failed);
        var watermarks = new KafkaSourceWatermarks(() -> admin);

        assertThatThrownBy(watermarks::current).isInstanceOf(IllegalStateException.class);
    }
}
