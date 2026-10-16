package io.github.temporalrift.timeline;

import java.util.Map;
import java.util.function.Consumer;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;

/**
 * Plays the simulation host: supplies {@code simulationEventSink} from a parent context and starts the application
 * under the {@code in-memory} profile on an ephemeral port, as the embedding contract describes.
 */
public final class InMemoryTimelineHost implements AutoCloseable {

    private final AnnotationConfigApplicationContext hostContext;
    private final ConfigurableApplicationContext application;

    private InMemoryTimelineHost(
            AnnotationConfigApplicationContext hostContext, ConfigurableApplicationContext application) {
        this.hostContext = hostContext;
        this.application = application;
    }

    public static InMemoryTimelineHost start(Consumer<Map<String, Object>> sink) {
        var host = hostWith(sink);
        return new InMemoryTimelineHost(host, run(host, DEFAULT_ARGUMENTS));
    }

    /** Starts the application as {@link #start} does, with the given arguments, for tests expecting a failure. */
    public static ConfigurableApplicationContext run(AnnotationConfigApplicationContext host, String... arguments) {
        return new SpringApplicationBuilder(TimelineServiceApplication.class)
                .parent(host)
                .profiles("in-memory", "test")
                .run(arguments);
    }

    /** Plays the host's command line: an ephemeral port, the simulation flag, and no config server. */
    public static final String[] DEFAULT_ARGUMENTS = {
        "--server.port=0", "--game.simulation.enabled=true", "--spring.cloud.config.enabled=false"
    };

    public static AnnotationConfigApplicationContext hostWith(Consumer<Map<String, Object>> sink) {
        var host = new AnnotationConfigApplicationContext();
        host.registerBean("simulationEventSink", Consumer.class, () -> sink);
        host.refresh();
        return host;
    }

    /** Pins the isolated execution the simulation flow needs before it can resolve anything. */
    public InMemoryTimelineHost configureExecution() {
        bean(ConfigureSimulationExecutionUseCase.class).handle(ExecutionContextTestData.context("42"));
        return this;
    }

    public ConfigurableApplicationContext application() {
        return application;
    }

    public <T> T bean(Class<T> type) {
        return application.getBean(type);
    }

    @SuppressWarnings("unchecked")
    public Consumer<Map<String, Object>> delivery() {
        return application.getBean("simulationEventDelivery", Consumer.class);
    }

    public int port() {
        return Integer.parseInt(application.getEnvironment().getProperty("local.server.port"));
    }

    @Override
    public void close() {
        application.close();
        hostContext.close();
    }
}
