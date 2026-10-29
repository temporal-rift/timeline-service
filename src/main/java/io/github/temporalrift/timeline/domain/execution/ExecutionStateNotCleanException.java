package io.github.temporalrift.timeline.domain.execution;

/** No context is open, yet gameplay of an earlier context is still held: the lane needs a reset, not a retry. */
public class ExecutionStateNotCleanException extends RuntimeException {

    public ExecutionStateNotCleanException() {
        super("Gameplay of an earlier execution context is still held");
    }
}
