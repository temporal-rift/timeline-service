package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;

/**
 * Exercises spring.config.import=configserver:... end to end against an in-process HTTP stub serving the
 * Config Server's real /{application}/{profile} response shape, rather than a shared Docker image from the
 * infrastructure repo (see design.md Decision 3 of the timeline-config-client change).
 */
class TimelineRulesPropertiesConfigServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer configServerStub;

    @AfterEach
    void stopStub() {
        if (configServerStub != null) {
            configServerStub.stop(0);
        }
    }

    @Test
    void bindsValuesFromConfigServer_whenNoLocalOverridePresent() throws IOException {
        configServerStub = startStub(completeSource());

        try (var context = startContext(configServerStub)) {
            var props = context.getBean(TimelineRulesProperties.class);
            assertThat(props.pushShift(CardGrade.I)).isEqualTo(10);
            assertThat(props.pushShift(CardGrade.II)).isEqualTo(20);
            assertThat(props.pushShift(CardGrade.III)).isEqualTo(30);
            assertThat(props.suppressShift(CardGrade.II)).isEqualTo(-20);
            assertThat(props.swingShift(CardGrade.II)).isEqualTo(30);
            assertThat(props.amplifyMultiplier(CardGrade.III)).isEqualTo(3.0);
            assertThat(props.bandLowMax()).isEqualTo(30);
            assertThat(props.bandMediumMax()).isEqualTo(60);
        }
    }

    @Test
    void localOverride_winsOverConfigServerValue() throws IOException {
        configServerStub = startStub(completeSource());

        try (var context = startContext(configServerStub, "game.rules.probability.push-shift.II=999")) {
            var props = context.getBean(TimelineRulesProperties.class);
            assertThat(props.pushShift(CardGrade.II)).isEqualTo(999);
            // Every other key still comes from the Config Server stub, untouched by the override.
            assertThat(props.pushShift(CardGrade.I)).isEqualTo(10);
            assertThat(props.bandLowMax()).isEqualTo(30);
        }
    }

    @Test
    void incompleteEffectiveConfiguration_stillFailsFast() throws IOException {
        var incompletePushShift = completeSource();
        incompletePushShift.remove("game.rules.probability.push-shift.III");
        configServerStub = startStub(incompletePushShift);

        assertThatThrownBy(() -> startContext(configServerStub)).isInstanceOf(RuntimeException.class);
    }

    private static ConfigurableApplicationContext startContext(HttpServer stub, String... localOverrides) {
        // Passed as --key=value command-line args (Spring Boot's highest-priority source) rather than via
        // SpringApplicationBuilder#properties (its lowest-priority default properties source) — the latter
        // would be silently shadowed by this module's own classpath application.yml, which is on the test
        // classpath too and declares its own spring.config.import pointing at the real Compose config-server.
        var baseUrl = "http://localhost:" + stub.getAddress().getPort();
        var args = new ArrayList<String>(List.of(
                // A config-name with no matching classpath file, so this module's own application.yml (and
                // its own, unrelated spring.config.import) is never picked up alongside the stub's import.
                "--spring.config.name=timeline-rules-properties-config-server-test",
                "--spring.application.name=timeline-service",
                "--spring.config.import=configserver:" + baseUrl,
                "--spring.cloud.config.fail-fast=true",
                "--game.rules.probability.floor=0",
                "--game.rules.probability.ceiling=90",
                "--game.rules.probability.momentum-bonus=10",
                "--game.rules.probability.rally-multiplier=1.5"));
        for (var override : localOverrides) {
            args.add("--" + override);
        }
        return new SpringApplicationBuilder(RulesPropertiesConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private static LinkedHashMap<String, Object> completeSource() {
        var source = new LinkedHashMap<String, Object>();
        source.put("game.rules.probability.push-shift.I", 10);
        source.put("game.rules.probability.push-shift.II", 20);
        source.put("game.rules.probability.push-shift.III", 30);
        source.put("game.rules.probability.suppress-shift.I", -10);
        source.put("game.rules.probability.suppress-shift.II", -20);
        source.put("game.rules.probability.suppress-shift.III", -30);
        source.put("game.rules.probability.swing-shift.I", 15);
        source.put("game.rules.probability.swing-shift.II", 30);
        source.put("game.rules.probability.swing-shift.III", 45);
        source.put("game.rules.probability.amplify-multiplier.I", 1.5);
        source.put("game.rules.probability.amplify-multiplier.II", 2.0);
        source.put("game.rules.probability.amplify-multiplier.III", 3.0);
        source.put("game.rules.probability.band-low-max", 30);
        source.put("game.rules.probability.band-medium-max", 60);
        return source;
    }

    private static HttpServer startStub(Map<String, Object> source) throws IOException {
        var responseBody = configServerResponseJson(source);
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String configServerResponseJson(Map<String, Object> source) throws IOException {
        var body = Map.of(
                "name", "timeline-service",
                "profiles", List.of("default"),
                "propertySources", List.of(Map.of("name", "test-stub", "source", source)));
        return JSON.writeValueAsString(body);
    }

    @Configuration
    @EnableConfigurationProperties(TimelineRulesProperties.class)
    static class RulesPropertiesConfiguration {}
}
