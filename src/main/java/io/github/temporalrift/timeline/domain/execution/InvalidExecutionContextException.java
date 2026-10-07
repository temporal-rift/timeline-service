package io.github.temporalrift.timeline.domain.execution;

public class InvalidExecutionContextException extends RuntimeException {

    public InvalidExecutionContextException(String message) {
        super(message);
    }
}
