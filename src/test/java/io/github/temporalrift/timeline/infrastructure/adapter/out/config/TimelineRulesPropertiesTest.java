package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;

class TimelineRulesPropertiesTest {

    private static final Map<CardGrade, Integer> PUSH = Map.of(CardGrade.I, 10, CardGrade.II, 20, CardGrade.III, 30);
    private static final Map<CardGrade, Integer> SUPPRESS =
            Map.of(CardGrade.I, -10, CardGrade.II, -20, CardGrade.III, -30);
    private static final Map<CardGrade, Integer> SWING = Map.of(CardGrade.I, 15, CardGrade.II, 30, CardGrade.III, 45);
    private static final Map<CardGrade, Double> AMPLIFY =
            Map.of(CardGrade.I, 1.5, CardGrade.II, 2.0, CardGrade.III, 3.0);

    @Test
    void validBounds_doNotThrow() {
        assertThatCode(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5))
                .doesNotThrowAnyException();
    }

    @Test
    void floorGreaterThanCeiling_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 90, 0, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floorBelowZero_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, -1, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceilingAboveHundred_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 101, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void narrowBand_breaksPairFeasibility_throws() {
        // floor=30, ceiling=50: 2*floor + ceiling = 110 > 100 — a desiredTarget of 50 would need the
        // other two outcomes to sum to 50, but each must be at least 30, i.e. at least 60 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 30, 50, 30, 40, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wideCeilingNarrowFloor_breaksPairFeasibility_throws() {
        // floor=0, ceiling=40: floor + 2*ceiling = 80 < 100 — a desiredTarget of 0 would need the other
        // two outcomes to sum to 100, but each can be at most 40, i.e. at most 80 combined.
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 40, 30, 35, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePushShift_throws() {
        var invalidPush = Map.of(CardGrade.I, 10, CardGrade.II, 0, CardGrade.III, 30);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(invalidPush, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonNegativeSuppressShift_throws() {
        var invalidSuppress = Map.of(CardGrade.I, -10, CardGrade.II, 0, CardGrade.III, -30);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(PUSH, invalidSuppress, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveSwingShift_throws() {
        var invalidSwing = Map.of(CardGrade.I, 15, CardGrade.II, 0, CardGrade.III, 45);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(PUSH, SUPPRESS, invalidSwing, AMPLIFY, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amplifyMultiplierNotGreaterThanOne_throws() {
        var invalidAmplify = Map.of(CardGrade.I, 1.5, CardGrade.II, 1.0, CardGrade.III, 3.0);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(PUSH, SUPPRESS, SWING, invalidAmplify, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingPushGradeEntry_throws() {
        var incompletePush = Map.of(CardGrade.I, 10, CardGrade.II, 20);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(incompletePush, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingAmplifyGradeEntry_throws() {
        var incompleteAmplify = Map.of(CardGrade.I, 1.5, CardGrade.II, 2.0);
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(PUSH, SUPPRESS, SWING, incompleteAmplify, 0, 90, 30, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandLowMaxBelowZero_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, -1, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandMediumMaxNotGreaterThanBandLowMax_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 60, 60, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandMediumMaxAboveHundred_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 101, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveMomentumBonus_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rallyMultiplierNotGreaterThanOne_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rallyMultiplierNaN_throws() {
        assertThatThrownBy(() ->
                        new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rallyMultiplierPositiveInfinity_throws() {
        assertThatThrownBy(() -> new TimelineRulesProperties(
                        PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rallyMultiplierOverflowsBoostedMagnitude_throws() {
        // swingShift(45) * amplifyMultiplier(3.0) * 4e17 vastly exceeds Integer.MAX_VALUE — finite, but would
        // silently wrap once rallyAdjustedMagnitude rounds and narrows it to an int.
        assertThatThrownBy(() -> new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 4e17))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pushShift_looksUpConfiguredGrade() {
        var props = new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5);
        assertThat(props.pushShift(CardGrade.I)).hasValue(10);
        assertThat(props.pushShift(CardGrade.III)).hasValue(30);
    }

    @Test
    void amplifyMultiplier_looksUpConfiguredGrade() {
        var props = new TimelineRulesProperties(PUSH, SUPPRESS, SWING, AMPLIFY, 0, 90, 30, 60, 10, 1.5);
        assertThat(props.amplifyMultiplier(CardGrade.III)).hasValue(3.0);
    }
}
