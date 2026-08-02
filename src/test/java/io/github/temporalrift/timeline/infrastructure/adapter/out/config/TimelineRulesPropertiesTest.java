package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TimelineRulesPropertiesTest {

    @Test
    void validBounds_doNotThrow() {
        assertThatCode(() -> new TimelineRulesProperties(20, -20, 30, 0, 90)).doesNotThrowAnyException();
    }

    @Test
    void floorGreaterThanCeiling_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 90, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floorBelowZero_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, -1, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceilingAboveHundred_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrowBand_breaksPairFeasibility_throws() {
        // floor=30, ceiling=50: 2*floor + ceiling = 110 > 100 — a desiredTarget of 50 would need the
        // other two outcomes to sum to 50, but each must be at least 30, i.e. at least 60 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 30, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wideCeilingNarrowFloor_breaksPairFeasibility_throws() {
        // floor=0, ceiling=40: floor + 2*ceiling = 80 < 100 — a desiredTarget of 0 would need the other
        // two outcomes to sum to 100, but each can be at most 40, i.e. at most 80 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 40))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePushShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(0, -20, 30, 0, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonNegativeSuppressShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, 0, 30, 0, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveSwingShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 0, 0, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
