package io.github.temporalrift.timeline.infrastructure.adapter.out.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class LogicalClockTest {

    private static final Instant START = ExecutionContextTestData.START;

    @Mock
    ExecutionRepository executions;

    @Test
    @DisplayName("before configuration nothing can look due")
    void instant_beforeConfiguration_isEpoch() {
        given(executions.find()).willReturn(Optional.empty());

        assertThat(new LogicalClock(executions).instant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("after a restart the clock resumes from the stored logical time")
    void instant_afterRestart_readsStoredTime() {
        given(executions.find()).willReturn(Optional.of(Execution.configure(ExecutionContextTestData.context("1"))));

        assertThat(new LogicalClock(executions).instant()).isEqualTo(START);
    }

    @Test
    @DisplayName("the clock only moves forward")
    void advanceTo_earlierInstant_isIgnored() {
        var clock = new LogicalClock(executions);

        clock.advanceTo(START.plusSeconds(60));
        clock.advanceTo(START);

        assertThat(clock.instant()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    @DisplayName("the clock is a UTC clock and identifies only itself")
    void clock_isUtcAndIdentityEqual() {
        var clock = new LogicalClock(executions);

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneOffset.ofHours(2))).isSameAs(clock);
        assertThat(clock)
                .isEqualTo(clock)
                .isNotEqualTo(new LogicalClock(executions))
                .hasSameHashCodeAs(clock);
    }
}
