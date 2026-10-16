package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;

@Component
@Profile("in-memory")
class InMemoryFutureEventEraIndexAdapter implements FutureEventEraIndexPort {

    private final Table<UUID, Entry> index;

    InMemoryFutureEventEraIndexAdapter(InMemoryDatabase database) {
        this.index = database.table(TableNames.FUTURE_EVENT_ERA_INDEX);
    }

    @Override
    public void add(UUID eventId, UUID gameId, int eraNumber, int revealIndex) {
        index.put(eventId, new Entry(eventId, gameId, eraNumber, revealIndex));
    }

    @Override
    public List<IndexedEventId> findByGameIdAndEraNumber(UUID gameId, int eraNumber) {
        return index.list(entry -> entry.gameId().equals(gameId) && entry.eraNumber() == eraNumber).stream()
                .sorted(Comparator.comparingInt(Entry::revealIndex))
                .map(entry -> new IndexedEventId(entry.eventId(), entry.revealIndex()))
                .toList();
    }

    private record Entry(UUID eventId, UUID gameId, int eraNumber, int revealIndex) {}
}
