package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

class InMemoryStartupTest {

    @Test
    @DisplayName("starts and serves REST on an ephemeral port with no DataSource and no Kafka client")
    void starts_withoutInfrastructure() throws Exception {
        try (var host = InMemoryTimelineHost.start(record -> {})) {
            var context = host.application();

            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(KafkaTemplate.class)).isEmpty();
            assertThat(host.port()).isPositive();
            var response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + host.port() + "/actuator/health"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("fails naming game.simulation.enabled when the simulation flag is off")
    void fails_whenSimulationFlagIsOff() {
        try (var host = InMemoryTimelineHost.hostWith(record -> {})) {
            assertThatThrownBy(() ->
                            InMemoryTimelineHost.run(host, "--server.port=0", "--spring.cloud.config.enabled=false"))
                    .hasStackTraceContaining("game.simulation.enabled");
        }
    }

    @Test
    @DisplayName("fails naming simulationEventSink when the host supplies none")
    void fails_whenSinkIsMissing() {
        try (var host = new AnnotationConfigApplicationContext()) {
            host.refresh();
            assertThatThrownBy(() -> InMemoryTimelineHost.run(host, InMemoryTimelineHost.DEFAULT_ARGUMENTS))
                    .hasStackTraceContaining("simulationEventSink");
        }
    }

    @Test
    @DisplayName("exposes the inbound delivery bean under its contract name")
    void exposesDeliveryBean() {
        try (var host = InMemoryTimelineHost.start(record -> {})) {
            Consumer<Map<String, Object>> delivery = host.delivery();

            assertThat(delivery).isNotNull();
        }
    }
}
