package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.membership.MemberFaction;

public final class ExecutionContextTestData {

    public static final UUID CASE_KEY = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    public static final String MANIFEST_DIGEST = "a".repeat(64);
    public static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    public static final UUID PLAYER_0 = UUID.fromString("00000000-0000-4000-8000-000000000010");
    public static final UUID PLAYER_1 = UUID.fromString("00000000-0000-4000-8000-000000000011");
    public static final UUID PLAYER_2 = UUID.fromString("00000000-0000-4000-8000-000000000012");

    private ExecutionContextTestData() {}

    public static ExecutionContext context(String seed) {
        return new ExecutionContext(
                CASE_KEY, new Seed(seed), EntropyVersion.SHA256_V1, MANIFEST_DIGEST, START, threeSeats());
    }

    public static List<SimulationSeat> threeSeats() {
        return List.of(
                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                new SimulationSeat(2, PLAYER_2, MemberFaction.WEAVERS));
    }
}
