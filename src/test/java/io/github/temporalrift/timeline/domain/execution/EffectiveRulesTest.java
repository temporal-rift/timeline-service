package io.github.temporalrift.timeline.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EffectiveRulesTest {

    private static final String EXAMPLE_DIGEST = "2f4e77d6f2f5ad41eb3b05de091e524133995ef6f274a58f539adbf2d5b06fad";

    @Test
    @DisplayName("the canonical form and digest match the published EffectiveRules example")
    void canonicalJson_matchesThePublishedExample() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("game.rules.win-score-threshold", 20);
        properties.put("game.rules.probability.amplify-multiplier.II", 2);

        var rules = new EffectiveRules(properties);

        assertThat(rules.canonicalJson())
                .isEqualTo(
                        "{\"game.rules.probability.amplify-multiplier.II\":2,\"game.rules.win-score-threshold\":20}");
        assertThat(rules.digest()).isEqualTo(EXAMPLE_DIGEST);
    }

    @Test
    @DisplayName("key order and a whole-number spelling do not change the digest")
    void digest_isIndependentOfKeyOrderAndNumberSpelling() {
        var first = new LinkedHashMap<String, Object>();
        first.put("game.rules.win-score-threshold", 20);
        first.put("game.rules.probability.amplify-multiplier.II", 2.0);

        var second = new LinkedHashMap<String, Object>();
        second.put("game.rules.probability.amplify-multiplier.II", 2);
        second.put("game.rules.win-score-threshold", 20);

        assertThat(new EffectiveRules(first).digest()).isEqualTo(new EffectiveRules(second).digest());
        assertThat(new EffectiveRules(first).canonicalJson()).isEqualTo(new EffectiveRules(second).canonicalJson());
    }

    @Test
    @DisplayName("arrays keep their order and are bound with indexed keys")
    void arrays_areCanonicalAndBindable() {
        var rules = new EffectiveRules(Map.of("game.rules.scoring.bands", List.of(10, 20, 30)));

        assertThat(rules.canonicalJson()).isEqualTo("{\"game.rules.scoring.bands\":[10,20,30]}");
        assertThat(rules.bindable())
                .containsEntry("game.rules.scoring.bands[0]", "10")
                .containsEntry("game.rules.scoring.bands[2]", "30");
    }

    @Test
    @DisplayName("numbers normalise to their IEEE-754 double value, so canonical storage round-trips")
    void numbers_normaliseToTheirDoubleValue() {
        var posted = new EffectiveRules(Map.of("game.rules.large", 9007199254740993L));
        var reloaded = new EffectiveRules(Map.of("game.rules.large", 9007199254740992L));

        assertThat(posted).isEqualTo(reloaded);
        assertThat(posted.canonicalJson()).isEqualTo(reloaded.canonicalJson());
    }

    @Test
    @DisplayName("ECMAScript number spelling and JSON string escapes are canonical")
    void canonicalJson_usesEcmaNumbersAndEscapesStrings() {
        var text = "\"" + "\\" + "\n" + "\t";
        var rules = new EffectiveRules(Map.of(
                "game.rules.a",
                1.5,
                "game.rules.b",
                1e-7,
                "game.rules.c",
                1e21,
                "game.rules.d",
                -2.5,
                "game.rules.e",
                true,
                "game.rules.f",
                text,
                "game.rules.g",
                List.of("a", 2, false)));
        var canonical = rules.canonicalJson();

        assertThat(canonical)
                .contains("\"game.rules.a\":1.5")
                .contains("\"game.rules.b\":1e-7")
                .contains("\"game.rules.c\":1e+21")
                .contains("\"game.rules.d\":-2.5")
                .contains("\"game.rules.e\":true")
                .contains("\"game.rules.g\":[\"a\",2,false]")
                .contains("\\\"")
                .contains("\\\\")
                .contains("\\" + "n")
                .contains("\\" + "t");
        assertThat(rules.bindable()).containsEntry("game.rules.f", text);
    }

    @Test
    @DisplayName("a non-finite number is rejected")
    void nonFiniteNumber_isRejected() {
        Map<String, Object> properties = Map.of("game.rules.nan", Double.NaN);

        assertThatThrownBy(() -> new EffectiveRules(properties))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("game.rules.nan");
    }

    @Test
    @DisplayName("a null properties map is an execution-context error, not a null pointer")
    void nullProperties_isAnInvalidContext() {
        assertThatThrownBy(() -> new EffectiveRules(null))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("rules.properties");
    }

    @Test
    @DisplayName("a property outside the game.rules namespace is rejected")
    void propertyOutsideTheNamespace_isRejected() {
        Map<String, Object> properties = Map.of("game.timers.interval", 1);

        assertThatThrownBy(() -> new EffectiveRules(properties))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("game.timers.interval");
    }

    @Test
    @DisplayName("a value outside the rule value grammar is rejected")
    void unsupportedValue_isRejected() {
        Map<String, Object> properties = Map.of("game.rules.nested", Map.of("a", 1));

        assertThatThrownBy(() -> new EffectiveRules(properties))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("game.rules.nested");
    }
}
