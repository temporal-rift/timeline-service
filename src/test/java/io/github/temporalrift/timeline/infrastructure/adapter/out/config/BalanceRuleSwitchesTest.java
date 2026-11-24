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
import io.github.temporalrift.timeline.domain.port.out.ForesightRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TapestryRulesPort;

/** The Tapestry and Foresight rules bind with their defaults and are served from an execution context. */
class BalanceRuleSwitchesTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ExecutionRulesBinding<TapestryRulesProperties> tapestry =
            new ExecutionRulesBinding<>(TapestryRulesProperties.class, "game.rules", VALIDATOR);
    private final ExecutionRulesBinding<ForesightRulesProperties> foresight =
            new ExecutionRulesBinding<>(ForesightRulesProperties.class, "game.rules.foresight", VALIDATOR);

    @Test
    void tapestryKeepsTheConflictUnlessConfiguredToAbsorbIt() {
        assertThat(tapestry.bind(Map.of()).tapestryAbsorbsChainConflict()).isFalse();
        assertThat(tapestry.bind(Map.of("game.rules.tapestry-absorbs-chain-conflict", "false"))
                        .tapestryAbsorbsChainConflict())
                .isFalse();
        assertThat(tapestry.bind(Map.of("game.rules.tapestry-absorbs-chain-conflict", "true"))
                        .tapestryAbsorbsChainConflict())
                .isTrue();
        assertThat(ExecutionRulesBinding.requiredKeys(TapestryRulesProperties.class, "game.rules"))
                .isEmpty();
    }

    @Test
    void aContextServesItsOwnTapestryRule() {
        var context =
                new ExecutionRules.Rules("t".repeat(64), Map.of("game.rules.tapestry-absorbs-chain-conflict", "true"));
        ExecutionRules active = () -> Optional.of(context);

        var port = ExecutionRulesBinding.servedFrom(
                TapestryRulesPort.class, active, tapestry, new TapestryRulesProperties(false));

        assertThat(port.tapestryAbsorbsChainConflict()).isTrue();
    }

    @Test
    void writingsPerEraDefaultsToOneAndRejectsZero() {
        assertThat(foresight.bind(Map.of()).writingsPerEra()).isEqualTo(1);
        assertThat(foresight
                        .bind(Map.of("game.rules.foresight.writings-per-era", "3"))
                        .writingsPerEra())
                .isEqualTo(3);
        var zero = Map.of("game.rules.foresight.writings-per-era", "0");
        assertThatThrownBy(() -> foresight.bind(zero))
                .isInstanceOf(InvalidExecutionRulesException.class)
                .hasMessageContaining("game.rules.foresight.writings-per-era");
    }

    @Test
    void betWindowDefaultsToRoundTwoAndRejectsValuesOutsideOneToThree() {
        var window = new ExecutionRulesBinding<>(FactionBetWindowRulesProperties.class, "game.rules", VALIDATOR);
        assertThat(window.bind(Map.of()).factionBetLatestRound()).isEqualTo(2);
        assertThat(window.bind(Map.of("game.rules.faction-bet-latest-round", "3"))
                        .factionBetLatestRound())
                .isEqualTo(3);
        for (var invalid : new String[] {"0", "4"}) {
            var rules = Map.of("game.rules.faction-bet-latest-round", invalid);
            assertThatThrownBy(() -> window.bind(rules))
                    .isInstanceOf(InvalidExecutionRulesException.class)
                    .hasMessageContaining("game.rules.faction-bet-latest-round");
        }
    }

    @Test
    void aContextServesItsOwnWritingCap() {
        var context = new ExecutionRules.Rules("f".repeat(64), Map.of("game.rules.foresight.writings-per-era", "3"));
        ExecutionRules active = () -> Optional.of(context);

        var port = ExecutionRulesBinding.servedFrom(
                ForesightRulesPort.class, active, foresight, new ForesightRulesProperties(1));

        assertThat(port.writingsPerEra()).isEqualTo(3);
    }
}
