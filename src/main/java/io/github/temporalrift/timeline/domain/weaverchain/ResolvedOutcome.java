package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/**
 * An outcome that actually resolved, sourced from {@code OutcomeApplied} history. Supplied by the caller so the
 * aggregate validates links without any persistence coupling.
 */
public record ResolvedOutcome(UUID eventId, UUID outcomeId) {}
