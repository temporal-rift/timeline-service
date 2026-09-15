package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import io.github.temporalrift.timeline.TimelineServiceIntegrationTest;

/**
 * Proves the Prometheus scrape endpoint is available without authentication and carries this
 * service's custom counters. Lives in the kafka package to reuse the package-private skip
 * metrics bean instead of duplicating its metric name literal.
 */
@TimelineServiceIntegrationTest
class PrometheusEndpointIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameEventSkipMetrics skipMetrics;

    @Test
    @DisplayName("Unauthenticated scrape of /actuator/prometheus responds 200 with the skip counter")
    void scrapeEndpoint_exposesConsumerSkipCounterWithoutAuthentication() throws Exception {
        skipMetrics.recordUnknownType();

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("timeline_kafka_consumer_skips")));
    }
}
