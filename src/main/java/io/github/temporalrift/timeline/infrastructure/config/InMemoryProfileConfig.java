package io.github.temporalrift.timeline.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;
import io.github.temporalrift.timeline.infrastructure.adapter.in.inmemory.InMemoryEventDelivery;
import io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory.InMemoryDatabase;
import io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory.InMemorySourceWatermarks;
import io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory.InMemoryTransactionManager;

/**
 * Runs the service without PostgreSQL or Kafka for an in-process simulation host. Everything stateful is a bean of this
 * context, so two application instances in one JVM share nothing. The host supplies {@code simulationEventSink} from
 * a parent context and drives inbound records through {@code simulationEventDelivery}.
 */
@Configuration
@Profile("in-memory")
class InMemoryProfileConfig {

    static final String SIMULATION_PROPERTY = "game.simulation.enabled";
    static final String EVENT_SINK_BEAN = "simulationEventSink";

    /** Fails before any bean is created, so the message names the missing piece rather than a wiring symptom. */
    @Bean
    static BeanFactoryPostProcessor inMemoryProfileGuard(Environment environment) {
        return beanFactory -> {
            if (!Boolean.parseBoolean(environment.getProperty(SIMULATION_PROPERTY))) {
                throw new IllegalStateException("The in-memory profile requires " + SIMULATION_PROPERTY + "=true");
            }
            if (!beanFactory.containsBean(EVENT_SINK_BEAN)) {
                throw new IllegalStateException("The in-memory profile requires the host to supply a "
                        + "Consumer<Map<String, Object>> bean named " + EVENT_SINK_BEAN);
            }
        };
    }

    @Bean
    InMemoryDatabase inMemoryDatabase() {
        return new InMemoryDatabase();
    }

    @Bean
    PlatformTransactionManager transactionManager(InMemoryDatabase database) {
        return new InMemoryTransactionManager(database);
    }

    @Bean("simulationEventDelivery")
    InMemoryEventDelivery simulationEventDelivery(ApplicationContext context) {
        return new InMemoryEventDelivery(context);
    }

    @Bean
    SourceWatermarks sourceWatermarks(InMemoryEventDelivery delivery) {
        return new InMemorySourceWatermarks(delivery::watermarks);
    }
}
