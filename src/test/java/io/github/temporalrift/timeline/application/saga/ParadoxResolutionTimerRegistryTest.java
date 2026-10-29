package io.github.temporalrift.timeline.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;

import io.github.temporalrift.timeline.domain.event.ExecutionClosed;

/** The close is published through the application context, as the close handler does, to the real listener. */
class ParadoxResolutionTimerRegistryTest {

    private static final Instant DEADLINE = Instant.parse("2026-03-01T10:00:02Z");

    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private AnnotationConfigApplicationContext context;
    private ParadoxResolutionTimerRegistry registry;
    private ParadoxResolutionTimerScheduler timerScheduler;

    @BeforeEach
    void setUp() {
        // A logical-time scheduler hands out timers that never fire, so nothing but the close can drop them.
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).willAnswer(invocation -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            scheduled.add(future);
            return future;
        });
        context = new AnnotationConfigApplicationContext();
        context.registerBean("paradoxTaskScheduler", TaskScheduler.class, () -> taskScheduler);
        context.registerBean(
                ParadoxResolutionTimeoutProcessor.class, () -> mock(ParadoxResolutionTimeoutProcessor.class));
        context.register(ParadoxResolutionTimerRegistry.class, ParadoxResolutionTimerScheduler.class);
        context.refresh();
        registry = context.getBean(ParadoxResolutionTimerRegistry.class);
        timerScheduler = context.getBean(ParadoxResolutionTimerScheduler.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("closing a context whose game opened three phases leaves no timer held, each one cancelled")
    void close_forgetsAndCancelsEveryTimerOfTheContext() {
        playCase(3);
        assertThat(registry.size()).isEqualTo(3);

        context.publishEvent(new ExecutionClosed(UUID.randomUUID()));

        assertThat(registry.size()).isZero();
        scheduled.forEach(future -> then(future).should().cancel(false));
    }

    @Test
    @DisplayName("a hundred cases played and closed in sequence hold no more timers than the first close left")
    void sequentialCases_doNotGrowTheRegistry() {
        playCase(3);
        context.publishEvent(new ExecutionClosed(UUID.randomUUID()));
        var afterFirstClose = registry.size();

        for (var played = 0; played < 100; played++) {
            playCase(3);
            context.publishEvent(new ExecutionClosed(UUID.randomUUID()));
        }

        assertThat(registry.size()).isEqualTo(afterFirstClose);
    }

    private void playCase(int phases) {
        for (var phase = 0; phase < phases; phase++) {
            timerScheduler.scheduleAfterCommit(UUID.randomUUID(), DEADLINE);
        }
    }
}
