package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class GameEventSkipMetrics {

    static final String METRIC_NAME = "timeline.kafka.consumer.skips";
    private static final String UNKNOWN_TYPE = "unknown_type";
    private static final String UNSUPPORTED_VERSION = "unsupported_version";

    private final MeterRegistry meterRegistry;

    GameEventSkipMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void recordUnknownType() {
        meterRegistry.counter(METRIC_NAME, "reason", UNKNOWN_TYPE).increment();
    }

    void recordUnsupportedVersion(String consumer) {
        meterRegistry
                .counter(METRIC_NAME, "reason", UNSUPPORTED_VERSION, "consumer", consumer)
                .increment();
    }
}
