package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.ParadoxContestRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxDetectionRulesPort;

/** The paradox switches default to on and are served from an execution context. */
class ParadoxRulesPropertiesTest {

    private static final String FATE_CONTRADICTION = "game.rules.paradox.fate-contradiction";
    private static final String CONTEST = "game.rules.paradox.detonate-contests-stabilize";
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ExecutionRulesBinding<ParadoxRulesProperties> binding =
            new ExecutionRulesBinding<>(ParadoxRulesProperties.class, "game.rules.paradox", VALIDATOR);

    @Test
    void fateContradictionIsOnUnlessSwitchedOff() {
        assertThat(binding.bind(Map.of()).fateContradiction()).isTrue();
        assertThat(binding.bind(Map.of(FATE_CONTRADICTION, "false")).fateContradiction())
                .isFalse();
        assertThat(ExecutionRulesBinding.requiredKeys(ParadoxRulesProperties.class, "game.rules.paradox"))
                .isEmpty();
    }

    @Test
    void contestIsOnUnlessSwitchedOff() {
        assertThat(binding.bind(Map.of()).detonateContestsStabilize()).isTrue();
        assertThat(binding.bind(Map.of(CONTEST, "false")).detonateContestsStabilize())
                .isFalse();
    }

    @Test
    void aContextServesItsOwnSwitches() {
        var context = new ExecutionRules.Rules("p".repeat(64), Map.of(FATE_CONTRADICTION, "false", CONTEST, "false"));
        ExecutionRules active = () -> Optional.of(context);
        var deployment = new ParadoxRulesProperties(true, true);

        var detection = ExecutionRulesBinding.servedFrom(ParadoxDetectionRulesPort.class, active, binding, deployment);
        var contest = ExecutionRulesBinding.servedFrom(ParadoxContestRulesPort.class, active, binding, deployment);

        assertThat(detection.fateContradiction()).isFalse();
        assertThat(contest.detonateContestsStabilize()).isFalse();
    }
}
