package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

class ExecutionRulesBindingTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ExecutionRulesBinding<TimelineRulesProperties> probability =
            new ExecutionRulesBinding<>(TimelineRulesProperties.class, "game.rules.probability", VALIDATOR);

    @Test
    @DisplayName("a served port rebinds when the open context's digest changes and falls back when it closes")
    void servedFrom_rebindsPerDigest() {
        var active = new ActiveRules();
        var deployment = probability.bind(flatRules());
        var port = ExecutionRulesBinding.servedFrom(ProbabilityRulesPort.class, active, probability, deployment);

        assertThat(port.pushShift(CardGrade.II)).isEqualTo(20);

        var modified = flatRules();
        modified.put("game.rules.probability.push-shift.II", "25");
        active.rules = new ExecutionRules.Rules("a".repeat(64), modified);
        assertThat(port.pushShift(CardGrade.II)).isEqualTo(25);

        active.rules = null;
        assertThat(port.pushShift(CardGrade.II)).isEqualTo(20);
        var alias = port;
        assertThat(port).isEqualTo(alias);
        assertThat(port.hashCode()).isEqualTo(System.identityHashCode(port));
        assertThat(port.toString()).contains("ProbabilityRulesPort");
    }

    private static final class ActiveRules implements ExecutionRules {

        private volatile Rules rules;

        @Override
        public java.util.Optional<Rules> current() {
            return java.util.Optional.ofNullable(rules);
        }
    }

    @Test
    @DisplayName("a complete context binds exactly as configuration does")
    void bind_completeRules_producesTheValues() {
        var properties = flatRules();

        var rules = probability.bind(properties);

        assertThat(rules.pushShift(CardGrade.II)).isEqualTo(20);
        assertThat(rules.probabilityCeiling()).isEqualTo(90);
        assertThat(rules.bandMediumMax()).isEqualTo(60);
    }

    @Test
    @DisplayName("an invalid per-grade value names the concrete key")
    void bind_invalidGradeValue_namesTheKey() {
        var properties = flatRules();
        properties.put("game.rules.probability.suppress-shift.I", "10");

        assertThatThrownBy(() -> probability.bind(properties))
                .isInstanceOf(InvalidExecutionRulesException.class)
                .hasMessageContaining("game.rules.probability.suppress-shift.I");
    }

    @Test
    @DisplayName("a missing per-grade value names the concrete key")
    void bind_missingGradeValue_namesTheKey() {
        var properties = flatRules();
        properties.remove("game.rules.probability.push-shift.III");

        assertThatThrownBy(() -> probability.bind(properties))
                .isInstanceOf(InvalidExecutionRulesException.class)
                .hasMessageContaining("game.rules.probability.push-shift.III");
    }

    @Test
    @DisplayName("the required keys are derived from the properties records rather than repeated by hand")
    void requiredKeys_followTheRecordComponents() {
        assertThat(ExecutionRulesBinding.requiredKeys(TimelineRulesProperties.class, "game.rules.probability"))
                .containsExactlyInAnyOrderElementsOf(flatRules().keySet().stream()
                        .filter(key -> key.startsWith("game.rules.probability."))
                        .toList());
        assertThat(ExecutionRulesBinding.requiredKeys(ParadoxResolutionRulesProperties.class, "game.rules"))
                .containsExactly("game.rules.paradox-resolution-timer-seconds");
    }

    @Test
    @DisplayName("a missing scalar key is reported even though primitive binding would default it")
    void requiredKeys_missingScalar_namesTheKey() {
        var properties = flatRules();
        properties.remove("game.rules.probability.floor");
        var required = new TimelineExecutionRulesConfiguration().executionRequiredRulesValidator();

        assertThatThrownBy(() -> required.validate(properties))
                .isInstanceOf(InvalidExecutionRulesException.class)
                .hasMessageContaining("game.rules.probability.floor");
    }

    @Test
    @DisplayName("the paradox-resolution timer binds from the game.rules namespace")
    void bind_paradoxTimer_binds() {
        var binding = new ExecutionRulesBinding<>(ParadoxResolutionRulesProperties.class, "game.rules", VALIDATOR);

        var rules = binding.bind(Map.of("game.rules.paradox-resolution-timer-seconds", "2"));

        assertThat(rules.paradoxResolutionTimerSeconds()).isEqualTo(2);
    }

    static LinkedHashMap<String, String> flatRules() {
        var properties = new LinkedHashMap<String, String>();
        properties.put("game.rules.probability.push-shift.I", "10");
        properties.put("game.rules.probability.push-shift.II", "20");
        properties.put("game.rules.probability.push-shift.III", "30");
        properties.put("game.rules.probability.suppress-shift.I", "-10");
        properties.put("game.rules.probability.suppress-shift.II", "-20");
        properties.put("game.rules.probability.suppress-shift.III", "-30");
        properties.put("game.rules.probability.swing-shift.I", "15");
        properties.put("game.rules.probability.swing-shift.II", "30");
        properties.put("game.rules.probability.swing-shift.III", "45");
        properties.put("game.rules.probability.amplify-multiplier.I", "1.5");
        properties.put("game.rules.probability.amplify-multiplier.II", "2.0");
        properties.put("game.rules.probability.amplify-multiplier.III", "3.0");
        properties.put("game.rules.probability.floor", "0");
        properties.put("game.rules.probability.ceiling", "90");
        properties.put("game.rules.probability.momentum-bonus", "10");
        properties.put("game.rules.probability.rally-multiplier", "1.5");
        properties.put("game.rules.probability.thread-shift", "10");
        properties.put("game.rules.probability.band-low-max", "30");
        properties.put("game.rules.probability.band-medium-max", "60");
        properties.put("game.rules.paradox-resolution-timer-seconds", "2");
        return properties;
    }
}
