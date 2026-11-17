package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRulesPort;

/** The Weaver chain's completion length comes from the shared victory key, in configuration and in a context. */
class WeaverChainRulesPropertiesTest {

    private static final String KEY = "game.rules.victory.weaver-chain-length";
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ExecutionRulesBinding<WeaverChainRulesProperties> binding =
            new ExecutionRulesBinding<>(WeaverChainRulesProperties.class, "game.rules.victory", VALIDATOR);

    @Test
    void theCompletionLengthIsTheSharedVictoryKey() {
        assertThat(binding.bind(Map.of(KEY, "4")).completionLength()).isEqualTo(4);
        assertThat(binding.bind(Map.of(KEY, "2")).completionLength()).isEqualTo(2);
    }

    @Test
    void aCompletionLengthBelowTwoIsRejectedNamingTheKey() {
        assertThatThrownBy(() -> binding.bind(Map.of(KEY, "1")))
                .isInstanceOf(InvalidExecutionRulesException.class)
                .hasMessageContaining(KEY);
    }

    @Test
    void theKeyIsRequired() {
        assertThat(ExecutionRulesBinding.requiredKeys(WeaverChainRulesProperties.class, "game.rules.victory"))
                .containsExactly(KEY);
    }

    @Test
    void aContextServesItsOwnCompletionLength() {
        var context = new ExecutionRules.Rules("c".repeat(64), Map.of(KEY, "4"));
        ExecutionRules active = () -> Optional.of(context);

        var port = ExecutionRulesBinding.servedFrom(
                WeaverChainRulesPort.class, active, binding, new WeaverChainRulesProperties(3));

        assertThat(port.completionLength()).isEqualTo(4);
    }

    @Test
    void withoutAContextTheDeploymentValueIsServed() {
        ExecutionRules none = Optional::empty;

        var port = ExecutionRulesBinding.servedFrom(
                WeaverChainRulesPort.class, none, binding, new WeaverChainRulesProperties(3));

        assertThat(port.completionLength()).isEqualTo(3);
    }
}
