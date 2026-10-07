package io.github.temporalrift.timeline.domain.execution;

public class ExecutionContextConflictException extends RuntimeException {

    public ExecutionContextConflictException(String message) {
        super(message);
    }
}
