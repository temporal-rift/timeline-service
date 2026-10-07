package io.github.temporalrift.timeline.domain.execution;

public class InvalidClockAdvanceException extends RuntimeException {

    public InvalidClockAdvanceException(String message) {
        super(message);
    }
}
