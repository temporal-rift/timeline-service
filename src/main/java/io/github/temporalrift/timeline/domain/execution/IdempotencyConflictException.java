package io.github.temporalrift.timeline.domain.execution;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(UUID operationId) {
        super("Operation " + operationId + " was already applied with a different body");
    }
}
