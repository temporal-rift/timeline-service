package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.UUID;

/** The unique key of a {@code processed_events} row: an event claimed by one logical consumer. */
record ProcessedClaim(UUID eventId, String consumer) {}
