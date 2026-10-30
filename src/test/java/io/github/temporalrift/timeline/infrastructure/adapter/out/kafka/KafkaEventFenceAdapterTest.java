package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class KafkaEventFenceAdapterTest {

    private static final String TOPIC = "game.events";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final Admin admin = mock(Admin.class);

    @Test
    @DisplayName("a capture records the broker end offset and fences records below it")
    void capture_recordsTheEndOffset() {
        givenEndOffsets(5L);
        givenStoredFence(5L);

        var fence = new KafkaEventFenceAdapter(jdbc, () -> admin);
        fence.capture();

        assertThat(fence.isFenced(TOPIC, 0, 4L)).isTrue();
        assertThat(fence.isFenced(TOPIC, 0, 5L)).isFalse();
        verify(admin).listOffsets(anyMap());
    }

    @Test
    @DisplayName("a missing topic fences nothing")
    void capture_missingTopic_fencesNothing() throws Exception {
        var failing = mock(KafkaFuture.class);
        when(failing.get(anyLong(), any())).thenThrow(new ExecutionException(new UnknownTopicOrPartitionException()));
        var described = mock(DescribeTopicsResult.class);
        when(described.topicNameValues()).thenReturn(Map.of(TOPIC, failing));
        when(admin.describeTopics(List.of(TOPIC))).thenReturn(described);

        var fence = new KafkaEventFenceAdapter(jdbc, () -> admin);
        fence.capture();

        assertThat(fence.isFenced(TOPIC, 0, 0L)).isFalse();
        verify(admin, never()).listOffsets(anyMap());
    }

    @Test
    @DisplayName("a broker that cannot report the end offsets fails the capture")
    void capture_brokerFailure_fails() {
        givenPartitions();
        var failing = new KafkaFutureImpl<ListOffsetsResultInfo>();
        failing.completeExceptionally(new TimeoutException("listOffsets"));
        when(admin.listOffsets(anyMap())).thenReturn(new ListOffsetsResult(Map.of(PARTITION, failing)));

        var fence = new KafkaEventFenceAdapter(jdbc, () -> admin);

        assertThatThrownBy(fence::capture)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not record the event fence");
    }

    @Test
    @DisplayName("the fence is loaded from storage on first use")
    void isFenced_loadsFromStorage() {
        givenStoredFence(3L);

        var fence = new KafkaEventFenceAdapter(jdbc, () -> admin);

        assertThat(fence.isFenced(TOPIC, 0, 2L)).isTrue();
        assertThat(fence.isFenced(TOPIC, 0, 3L)).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenStoredFence(long nextOffset) {
        when(jdbc.sql(contains("SELECT topic")).query(any(RowMapper.class)).list())
                .thenReturn((List) List.of(Map.entry(PARTITION, nextOffset)));
    }

    private void givenEndOffsets(long endOffset) {
        givenPartitions();
        var info = new ListOffsetsResultInfo(endOffset, 0L, Optional.empty());
        when(admin.listOffsets(anyMap()))
                .thenReturn(new ListOffsetsResult(Map.of(PARTITION, KafkaFuture.completedFuture(info))));
    }

    private void givenPartitions() {
        var partitionInfo = mock(TopicPartitionInfo.class);
        when(partitionInfo.partition()).thenReturn(0);
        var description = mock(TopicDescription.class);
        when(description.partitions()).thenReturn(List.of(partitionInfo));
        var described = mock(DescribeTopicsResult.class);
        when(described.topicNameValues()).thenReturn(Map.of(TOPIC, KafkaFuture.completedFuture(description)));
        when(admin.describeTopics(List.of(TOPIC))).thenReturn(described);
    }
}
