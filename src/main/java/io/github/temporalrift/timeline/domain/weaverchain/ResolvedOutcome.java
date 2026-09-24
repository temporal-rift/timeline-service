package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/**
 * An outcome that actually won its event, with the era it resolved in. Supplied by the caller so the aggregate
 * validates links without any persistence coupling.
 */
public record ResolvedOutcome(UUID eventId, UUID outcomeId, int eraNumber) {}
