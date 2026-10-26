package io.github.temporalrift.timeline.infrastructure.adapter.out.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.execution.EntropyVersion;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.Seed;
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
    @DisplayName("a load racing a reset never pins the closed context's time again")
    void instant_loadRacingReset_keepsTheNewContextsTime() throws Exception {
        var oldExecution = Execution.configure(ExecutionContextTestData.context("1"));
        var newExecution = Execution.configure(new ExecutionContext(
                ExecutionContextTestData.CASE_KEY,
                new Seed("2"),
                EntropyVersion.SHA256_V1,
                ExecutionContextTestData.MANIFEST_DIGEST,
                ExecutionContextTestData.RULES,
                ExecutionContextTestData.RULES_DIGEST,
                START.plusSeconds(5),
                ExecutionContextTestData.threeSeats()));
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repository = new ExecutionRepository() {
            @Override
            public Optional<Execution> find() {
                loading.countDown();
                await(release);
                return Optional.of(loads.getAndIncrement() == 0 ? oldExecution : newExecution);
            }

            @Override
            public Optional<Execution> findWithLock() {
                return find();
            }

            @Override
            public boolean insertIfAbsent(Execution execution) {
                return false;
            }

            @Override
            public void update(Execution execution) {
                // read-only fixture
            }

            @Override
            public Optional<Bounds> findBoundsForScope() {
                return Optional.empty();
            }

            @Override
            public Optional<Bounds> findBounds() {
                return Optional.empty();
            }

            @Override
            public boolean bindGame(UUID caseKey, UUID gameId) {
                return false;
            }

            @Override
            public boolean isClosedGame(UUID gameId) {
                return false;
            }

            @Override
            public void forgetClosedGames(UUID caseKey) {
                // read-only fixture
            }

            @Override
            public void close(UUID caseKey) {
                // read-only fixture
            }

            @Override
            public Optional<UUID> lastClosedCaseKey() {
                return Optional.empty();
            }
        };
        var clock = new LogicalClock(repository);

        var oldRead = CompletableFuture.supplyAsync(clock::instant);
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        var reset = CompletableFuture.runAsync(clock::reset);
        release.countDown();

        assertThat(oldRead.get(5, TimeUnit.SECONDS)).isEqualTo(START);
        reset.get(5, TimeUnit.SECONDS);
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(5));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
