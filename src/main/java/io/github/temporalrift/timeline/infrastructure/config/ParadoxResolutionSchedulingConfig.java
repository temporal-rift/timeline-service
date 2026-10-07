package io.github.temporalrift.timeline.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

@Configuration
class ParadoxResolutionSchedulingConfig {

    @Bean("paradoxTaskScheduler")
    TaskScheduler paradoxTaskScheduler(@Value("${game.simulation.enabled:false}") boolean logicalTime) {
        var scheduler = new TimerTaskScheduler(logicalTime);
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("paradox-resolution-timer-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
