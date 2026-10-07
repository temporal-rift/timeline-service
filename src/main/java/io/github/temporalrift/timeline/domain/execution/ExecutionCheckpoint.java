package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service-owned view of an execution. Drained means the work observed up to the listed watermarks completed; it
 * does not order this service against any other.
 */
public record ExecutionCheckpoint(
        UUID caseKey,
        String manifestDigest,
        long revision,
        Instant logicalTime,
        UUID gameId,
        ExecutionState state,
        boolean drained,
        int outboxPending,
        int continuationsPending,
        int dueTimersPending,
        Instant nextDeadline,
        List<SourceWatermark> sourceWatermarks) {}
