package io.github.temporalrift.timeline.infrastructure.config;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RandomnessConfig {

    @Bean
    RandomGenerator outcomeResolutionRandomGenerator() {
        return new SecureRandom();
    }
}
