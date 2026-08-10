package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TimelineRulesPropertiesTest {

    @Test
    void validBounds_doNotThrow() {
        assertThatCode(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, 30, 60, 10, 1.5))
                .doesNotThrowAnyException();
    }

    @Test
    void floorGreaterThanCeiling_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 90, 0, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floorBelowZero_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, -1, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceilingAboveHundred_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 101, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrowBand_breaksPairFeasibility_throws() {
        // floor=30, ceiling=50: 2*floor + ceiling = 110 > 100 — a desiredTarget of 50 would need the
        // other two outcomes to sum to 50, but each must be at least 30, i.e. at least 60 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 30, 50, 30, 40, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wideCeilingNarrowFloor_breaksPairFeasibility_throws() {
        // floor=0, ceiling=40: floor + 2*ceiling = 80 < 100 — a desiredTarget of 0 would need the other
        // two outcomes to sum to 100, but each can be at most 40, i.e. at most 80 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 40, 30, 35, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePushShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(0, -20, 30, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonNegativeSuppressShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, 0, 30, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveSwingShift_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 0, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandLowMaxBelowZero_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, -1, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandMediumMaxNotGreaterThanBandLowMax_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, 60, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandMediumMaxAboveHundred_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, 30, 101, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveMomentumBonus_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, 30, 60, 0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rallyMultiplierNotGreaterThanOne_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(20, -20, 30, 0, 90, 30, 60, 10, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
