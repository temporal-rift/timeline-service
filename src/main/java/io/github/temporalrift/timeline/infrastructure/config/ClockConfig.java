package io.github.temporalrift.timeline.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClockConfig {

    @Bean
    @ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "false", matchIfMissing = true)
    Clock clock() {
        return Clock.systemUTC();
    }
}
