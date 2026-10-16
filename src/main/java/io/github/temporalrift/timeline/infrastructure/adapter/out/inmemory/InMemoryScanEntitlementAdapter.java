package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;

@Component
@Profile("in-memory")
class InMemoryScanEntitlementAdapter implements ScanEntitlementPort {

    private final Table<Entitlement, Boolean> entitlements;

    InMemoryScanEntitlementAdapter(InMemoryDatabase database) {
        this.entitlements = database.table(TableNames.SCAN_ENTITLEMENT);
    }

    @Override
    @Transactional
    public void upsert(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {
        entitlements.insertIfAbsent(new Entitlement(gameId, eraNumber, playerId, eventId), Boolean.TRUE);
    }

    @Override
    public List<ScanEntitlement> findByGameAndEra(UUID gameId, int eraNumber) {
        return keys(key -> key.gameId().equals(gameId) && key.eraNumber() == eraNumber).stream()
                .map(key -> new ScanEntitlement(key.playerId(), key.eventId()))
                .toList();
    }

    @Override
    @Transactional
    public void deleteByGameAndEra(UUID gameId, int eraNumber) {
        keys(key -> key.gameId().equals(gameId) && key.eraNumber() == eraNumber).forEach(entitlements::remove);
    }

    @Override
    @Transactional
    public void deleteByGame(UUID gameId) {
        keys(key -> key.gameId().equals(gameId)).forEach(entitlements::remove);
    }

    private List<Entitlement> keys(Predicate<Entitlement> filter) {
        return entitlements.entries(row -> true).stream()
                .map(Map.Entry::getKey)
                .filter(filter)
                .toList();
    }

    private record Entitlement(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {}
}
