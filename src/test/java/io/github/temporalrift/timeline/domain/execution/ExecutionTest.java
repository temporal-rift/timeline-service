package io.github.temporalrift.timeline.domain.execution;

import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecutionTest {

    private static final UUID OPERATION = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001");

    private final Execution execution = Execution.configure(ExecutionContextTestData.context("42"));

    @Test
    @DisplayName("a configured execution starts at revision zero and at its context's logical time")
    void configure_startsAtRevisionZero() {
        assertThat(execution.revision()).isZero();
        assertThat(execution.logicalTime()).isEqualTo(START);
    }

    @Test
    @DisplayName("an advance at the expected revision moves time forward and bumps the revision once")
    void advance_atExpectedRevision_appliesOnce() {
        var target = START.plusSeconds(61);

        var outcome = execution.advance(new ClockAdvance(OPERATION, 0, target), null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.execution().revision()).isEqualTo(1);
        assertThat(outcome.execution().logicalTime()).isEqualTo(target);
        assertThat(outcome.operation().acknowledgement()).isEqualTo(new ClockAcknowledgement(OPERATION, 1, target));
    }

    @Test
    @DisplayName("a repeat of an applied operation returns the original acknowledgement and changes nothing")
    void advance_repeatedOperation_returnsOriginalAcknowledgement() {
        var request = new ClockAdvance(OPERATION, 0, START.plusSeconds(61));
        var first = execution.advance(request, null);
        var movedOn = first.execution()
                .advance(new ClockAdvance(UUID.randomUUID(), 1, START.plusSeconds(500)), null)
                .execution();

        var repeat = movedOn.advance(request, first.operation());

        assertThat(repeat.applied()).isFalse();
        assertThat(repeat.execution()).isSameAs(movedOn);
        assertThat(repeat.operation().acknowledgement())
                .isEqualTo(first.operation().acknowledgement());
    }

    @Test
    @DisplayName("an operation identifier reused with a different body is an idempotency conflict")
    void advance_changedBodyForKnownOperation_isIdempotencyConflict() {
        var first = execution.advance(new ClockAdvance(OPERATION, 0, START.plusSeconds(61)), null);
        var changed = new ClockAdvance(OPERATION, 0, START.plusSeconds(62));

        var applied = first.execution();
        var recorded = first.operation();

        assertThatThrownBy(() -> applied.advance(changed, recorded)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("a stale expected revision is rejected")
    void advance_staleRevision_isRejected() {
        var stale = new ClockAdvance(OPERATION, 3, START.plusSeconds(1));

        assertThatThrownBy(() -> execution.advance(stale, null)).isInstanceOf(StaleExecutionRevisionException.class);
    }

    @Test
    @DisplayName("a target before the current logical time is a clock regression")
    void advance_backwardTime_isClockRegression() {
        var backward = new ClockAdvance(OPERATION, 0, START.minusSeconds(1));

        assertThatThrownBy(() -> execution.advance(backward, null)).isInstanceOf(ClockRegressionException.class);
    }

    @Test
    @DisplayName("an equal-time advance is legal and still counts as a new revision")
    void advance_equalTime_isLegal() {
        var outcome = execution.advance(new ClockAdvance(OPERATION, 0, START), null);

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.execution().logicalTime()).isEqualTo(START);
        assertThat(outcome.execution().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("an advance without an operation id or a target time is not valid")
    void clockAdvance_missingFields_isInvalid() {
        assertThatThrownBy(() -> new ClockAdvance(null, 0, START)).isInstanceOf(InvalidClockAdvanceException.class);
        assertThatThrownBy(() -> new ClockAdvance(OPERATION, 0, null)).isInstanceOf(InvalidClockAdvanceException.class);
    }

    @Test
    @DisplayName("a negative expected revision is not a valid advance")
    void clockAdvance_negativeRevision_isInvalid() {
        var target = START.plus(Duration.ofSeconds(1));

        assertThatThrownBy(() -> new ClockAdvance(OPERATION, -1, target))
                .isInstanceOf(InvalidClockAdvanceException.class);
    }
}
