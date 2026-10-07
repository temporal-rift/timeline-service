package io.github.temporalrift.timeline.domain.port.out;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

import io.github.temporalrift.timeline.domain.execution.EntropyCoordinate;
import io.github.temporalrift.timeline.domain.execution.EntropyPurpose;
import io.github.temporalrift.timeline.domain.execution.IdentityKind;

/** Test entropy: every purpose shares one generator and identities are random, like an ordinary deployment. */
public final class StubExecutionEntropy implements ExecutionEntropy {

    private final RandomGenerator random;

    private StubExecutionEntropy(RandomGenerator random) {
        this.random = random;
    }

    public static StubExecutionEntropy unpredictable() {
        return new StubExecutionEntropy(new SecureRandom());
    }

    public static StubExecutionEntropy using(RandomGenerator random) {
        return new StubExecutionEntropy(random);
    }

    @Override
    public RandomGenerator generator(EntropyPurpose purpose, EntropyCoordinate coordinate) {
        return random;
    }

    @Override
    public UUID identity(IdentityKind kind, EntropyCoordinate coordinate) {
        return UUID.randomUUID();
    }
}
