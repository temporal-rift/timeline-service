package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.ParadoxDetectionRulesPort;

/** The fate-contradiction switch defaults to on and is served from an execution context. */
class ParadoxDetectionRulesPropertiesTest {

    private static final String KEY = "game.rules.paradox.fate-contradiction";
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ExecutionRulesBinding<ParadoxDetectionRulesProperties> binding =
            new ExecutionRulesBinding<>(ParadoxDetectionRulesProperties.class, "game.rules.paradox", VALIDATOR);

    @Test
    void fateContradictionIsOnUnlessSwitchedOff() {
        assertThat(binding.bind(Map.of()).fateContradiction()).isTrue();
        assertThat(binding.bind(Map.of(KEY, "false")).fateContradiction()).isFalse();
        assertThat(ExecutionRulesBinding.requiredKeys(ParadoxDetectionRulesProperties.class, "game.rules.paradox"))
                .isEmpty();
    }

    @Test
    void aContextServesItsOwnSwitch() {
        var context = new ExecutionRules.Rules("p".repeat(64), Map.of(KEY, "false"));
        ExecutionRules active = () -> Optional.of(context);

        var port = ExecutionRulesBinding.servedFrom(
                ParadoxDetectionRulesPort.class, active, binding, new ParadoxDetectionRulesProperties(true));

        assertThat(port.fateContradiction()).isFalse();
    }
}
