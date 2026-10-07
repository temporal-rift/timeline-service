package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;

/** Durable realized draws, so a redelivered or recovered step re-reads its original word instead of drawing anew. */
public interface EntropyDecisionRepository {

    Optional<Long> find(String purpose, String coordinate, int drawIndex);

    void save(String purpose, String coordinate, int drawIndex, long word);
}
