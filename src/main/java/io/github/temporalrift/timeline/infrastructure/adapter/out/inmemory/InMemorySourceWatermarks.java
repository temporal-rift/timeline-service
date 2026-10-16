package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.function.Supplier;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;

/** The in-memory delivery position of each consumer group, standing in for the committed Kafka offsets. */
public final class InMemorySourceWatermarks implements SourceWatermarks {

    private final Supplier<List<SourceWatermark>> deliveryPosition;

    public InMemorySourceWatermarks(Supplier<List<SourceWatermark>> deliveryPosition) {
        this.deliveryPosition = deliveryPosition;
    }

    @Override
    public List<SourceWatermark> current() {
        return deliveryPosition.get();
    }
}
