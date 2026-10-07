package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;

public interface SourceWatermarks {

    List<SourceWatermark> current();
}
