package io.github.temporalrift.timeline.domain.execution;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** An unsigned 64-bit seed held in its canonical decimal form. */
public record Seed(String decimal) {

    private static final Pattern CANONICAL = Pattern.compile("0|[1-9]\\d{0,19}");
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    public Seed {
        if (decimal == null || !CANONICAL.matcher(decimal).matches() || new BigInteger(decimal).compareTo(MAX) > 0) {
            throw new InvalidExecutionContextException("seed must be an unsigned 64-bit decimal integer");
        }
    }
}
