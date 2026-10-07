package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;

/** The configured execution of one isolated case: its pinned context, service-owned revision and logical time. */
public record Execution(ExecutionContext context, long revision, Instant logicalTime) {

    public static Execution configure(ExecutionContext context) {
        return new Execution(context, 0, context.logicalTime());
    }

    /** Applies a clock advance, or returns the operation recorded earlier for the same identifier. */
    public Advance advance(ClockAdvance request, ClockOperation recorded) {
        if (recorded != null) {
            if (!recorded.request().equals(request)) {
                throw new IdempotencyConflictException(request.operationId());
            }
            return new Advance(this, recorded, false);
        }
        if (request.expectedRevision() != revision) {
            throw new StaleExecutionRevisionException(request.expectedRevision(), revision);
        }
        if (request.targetTime().isBefore(logicalTime)) {
            throw new ClockRegressionException(request.targetTime(), logicalTime);
        }
        var advanced = new Execution(context, revision + 1, request.targetTime());
        var operation = new ClockOperation(
                request, new ClockAcknowledgement(request.operationId(), advanced.revision, advanced.logicalTime));
        return new Advance(advanced, operation, true);
    }

    public record Advance(Execution execution, ClockOperation operation, boolean applied) {}
}
