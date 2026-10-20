package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.membership.MemberFaction;

public final class ExecutionContextTestData {

    public static final UUID CASE_KEY = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    public static final String MANIFEST_DIGEST = "a".repeat(64);
    public static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    public static final UUID PLAYER_0 = UUID.fromString("00000000-0000-4000-8000-000000000010");
    public static final UUID PLAYER_1 = UUID.fromString("00000000-0000-4000-8000-000000000011");
    public static final UUID PLAYER_2 = UUID.fromString("00000000-0000-4000-8000-000000000012");
    public static final EffectiveRules RULES = rules(baselineProperties());
    public static final String RULES_DIGEST = RULES.digest();

    private ExecutionContextTestData() {}

    public static ExecutionContext context(String seed) {
        return new ExecutionContext(
                CASE_KEY,
                new Seed(seed),
                EntropyVersion.SHA256_V1,
                MANIFEST_DIGEST,
                RULES,
                RULES_DIGEST,
                START,
                threeSeats());
    }

    public static ExecutionContext context(String seed, EffectiveRules rules) {
        return new ExecutionContext(
                CASE_KEY,
                new Seed(seed),
                EntropyVersion.SHA256_V1,
                MANIFEST_DIGEST,
                rules,
                rules.digest(),
                START,
                threeSeats());
    }

    public static EffectiveRules rules(Map<String, Object> properties) {
        return new EffectiveRules(properties);
    }

    /** The rules the test profile's configuration serves; a context may instead carry its own variant of these. */
    public static Map<String, Object> baselineProperties() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("game.rules.probability.push-shift.I", 10);
        properties.put("game.rules.probability.push-shift.II", 20);
        properties.put("game.rules.probability.push-shift.III", 30);
        properties.put("game.rules.probability.suppress-shift.I", -10);
        properties.put("game.rules.probability.suppress-shift.II", -20);
        properties.put("game.rules.probability.suppress-shift.III", -30);
        properties.put("game.rules.probability.swing-shift.I", 15);
        properties.put("game.rules.probability.swing-shift.II", 30);
        properties.put("game.rules.probability.swing-shift.III", 45);
        properties.put("game.rules.probability.amplify-multiplier.I", 1.5);
        properties.put("game.rules.probability.amplify-multiplier.II", 2.0);
        properties.put("game.rules.probability.amplify-multiplier.III", 3.0);
        properties.put("game.rules.probability.floor", 0);
        properties.put("game.rules.probability.ceiling", 90);
        properties.put("game.rules.probability.momentum-bonus", 10);
        properties.put("game.rules.probability.rally-multiplier", 1.5);
        properties.put("game.rules.probability.thread-shift", 10);
        properties.put("game.rules.probability.band-low-max", 30);
        properties.put("game.rules.probability.band-medium-max", 60);
        properties.put("game.rules.paradox-resolution-timer-seconds", 2);
        return properties;
    }

    public static List<SimulationSeat> threeSeats() {
        return List.of(
                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                new SimulationSeat(2, PLAYER_2, MemberFaction.WEAVERS));
    }
}
