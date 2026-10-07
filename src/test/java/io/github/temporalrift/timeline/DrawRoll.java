package io.github.temporalrift.timeline;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Primary;

import io.github.temporalrift.timeline.domain.execution.EntropyCoordinate;
import io.github.temporalrift.timeline.domain.execution.EntropyPurpose;
import io.github.temporalrift.timeline.domain.execution.IdentityKind;
import io.github.temporalrift.timeline.domain.port.out.ExecutionEntropy;

/**
 * The outcome draw's entropy in integration tests: random unless a test pins the roll. No outcome can be
 * guaranteed, so a test that needs a known winner pins the roll to zero, which draws the first eligible outcome
 * holding weight, and releases it afterwards.
 */
@Primary
public class DrawRoll implements ExecutionEntropy {

    private final RandomGenerator random = new SecureRandom();
    private volatile Integer pinned;

    public void pin(int roll) {
        pinned = roll;
    }

    public void release() {
        pinned = null;
    }

    @Override
    public RandomGenerator generator(EntropyPurpose purpose, EntropyCoordinate coordinate) {
        return new RandomGenerator() {
            @Override
            public int nextInt(int bound) {
                var roll = pinned;
                return roll != null ? roll : random.nextInt(bound);
            }

            @Override
            public long nextLong() {
                return random.nextLong();
            }
        };
    }

    @Override
    public UUID identity(IdentityKind kind, EntropyCoordinate coordinate) {
        return UUID.randomUUID();
    }
}
