package io.github.temporalrift.timeline.domain.port.out;

import java.util.Map;

/** A check that an execution context's rules hold every rule the service uses, each in range. */
public interface ExecutionRulesValidator {

    /**
     * Fails with an {@link io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException} naming the
     * first rule that is missing or invalid.
     *
     * @param properties the rules as flat property keys to text values
     */
    void validate(Map<String, String> properties);
}
