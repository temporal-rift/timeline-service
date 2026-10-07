package io.github.temporalrift.timeline.domain.execution;

public record SourceWatermark(String groupId, String topic, int partition, long nextOffset) {}
