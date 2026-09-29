package io.github.temporalrift.timeline;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

import org.springframework.context.annotation.Primary;

/**
 * The outcome draw's random source in integration tests: random unless a test pins the roll. No outcome can be
 * guaranteed, so a test that needs a known winner pins the roll to zero, which draws the first eligible outcome
 * holding weight, and releases it afterwards.
 */
@Primary
public class DrawRoll implements RandomGenerator {

    private final RandomGenerator random = new SecureRandom();
    private volatile Long pinned;

    public void pin(long roll) {
        pinned = roll;
    }

    public void release() {
        pinned = null;
    }

    @Override
    public long nextLong() {
        var roll = pinned;
        return roll != null ? roll : random.nextLong();
    }
}
