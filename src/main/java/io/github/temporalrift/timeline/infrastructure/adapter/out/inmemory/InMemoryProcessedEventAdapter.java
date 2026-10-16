package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@Component
@Profile("in-memory")
class InMemoryProcessedEventAdapter implements ProcessedEventPort {

    private final Table<ProcessedClaim, Boolean> claims;

    InMemoryProcessedEventAdapter(InMemoryDatabase database) {
        this.claims = database.table(TableNames.PROCESSED_EVENTS);
    }

    /** Insert-first like {@code ON CONFLICT DO NOTHING}: a second claim waits for the first, then finds it taken. */
    @Override
    @Transactional
    public boolean claim(UUID eventId, String consumer) {
        return claims.insertIfAbsent(new ProcessedClaim(eventId, consumer), Boolean.TRUE);
    }
}
