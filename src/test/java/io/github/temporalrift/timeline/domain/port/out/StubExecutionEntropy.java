package io.github.temporalrift.timeline.domain.port.out;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

import io.github.temporalrift.timeline.domain.execution.EntropyCoordinate;
import io.github.temporalrift.timeline.domain.execution.EntropyPurpose;
import io.github.temporalrift.timeline.domain.execution.IdentityKind;

/** Test entropy: every purpose shares one generator; identities are random unless built to be deterministic. */
public final class StubExecutionEntropy implements ExecutionEntropy {

    private final RandomGenerator random;
    private final boolean deterministicIdentities;

    private StubExecutionEntropy(RandomGenerator random, boolean deterministicIdentities) {
        this.random = random;
        this.deterministicIdentities = deterministicIdentities;
    }

    public static StubExecutionEntropy unpredictable() {
        return new StubExecutionEntropy(new SecureRandom(), false);
    }

    public static StubExecutionEntropy using(RandomGenerator random) {
        return new StubExecutionEntropy(random, false);
    }

    /** Identities derived from kind and coordinate alone, as an isolated simulation deployment derives them. */
    public static StubExecutionEntropy deterministicIdentities() {
        return new StubExecutionEntropy(new SecureRandom(), true);
    }

    @Override
    public RandomGenerator generator(EntropyPurpose purpose, EntropyCoordinate coordinate) {
        return random;
    }

    @Override
    public UUID identity(IdentityKind kind, EntropyCoordinate coordinate) {
        if (!deterministicIdentities) {
            return UUID.randomUUID();
        }
        var name = kind.name() + "|" + coordinate.render(UUID::toString);
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
