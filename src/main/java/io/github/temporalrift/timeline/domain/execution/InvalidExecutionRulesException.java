package io.github.temporalrift.timeline.domain.execution;

/** The rules of an execution context are unusable; {@code key} names the property that failed. */
public class InvalidExecutionRulesException extends RuntimeException {

    private final String key;

    public InvalidExecutionRulesException(String key, String reason) {
        super(key + ": " + reason);
        this.key = key;
    }

    public InvalidExecutionRulesException(String key, String reason, Throwable cause) {
        super(key + ": " + reason, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
