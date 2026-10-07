package io.github.temporalrift.timeline.domain.futureevent;

import java.util.random.RandomGenerator;

/** An outcome draw pinned to one value, recording the bound the resolver asked for. */
public final class FixedDraw implements RandomGenerator {

    private final int roll;
    private int lastBound = -1;

    private FixedDraw(int roll) {
        this.roll = roll;
    }

    public static FixedDraw of(int roll) {
        return new FixedDraw(roll);
    }

    @Override
    public int nextInt(int bound) {
        if (roll >= bound) {
            throw new IllegalArgumentException("Pinned roll " + roll + " is outside [0, " + bound + ")");
        }
        lastBound = bound;
        return roll;
    }

    @Override
    public long nextLong() {
        throw new UnsupportedOperationException("The outcome draw only takes a bounded value");
    }

    public int lastBound() {
        return lastBound;
    }
}
