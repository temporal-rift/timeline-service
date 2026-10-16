package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;

@Component
@Profile("in-memory")
class InMemoryCascadeCarryForwardAdapter implements CascadeCarryForwardPort {

    private final Table<Row, UUID> rows;

    InMemoryCascadeCarryForwardAdapter(InMemoryDatabase database) {
        this.rows = database.table(TableNames.CASCADE_CARRY_FORWARD);
    }

    @Override
    @Transactional
    public void arm(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        rows.insertIfAbsent(new Row(gameId, eraNumber, eventId, outcomeId), playerId);
    }

    @Override
    public List<CascadeCarryForward> findByGameAndEra(UUID gameId, int eraNumber) {
        return rows.entries(playerId -> true).stream()
                .filter(entry ->
                        entry.getKey().gameId().equals(gameId) && entry.getKey().eraNumber() == eraNumber)
                .map(entry -> new CascadeCarryForward(
                        entry.getValue(),
                        entry.getKey().eventId(),
                        entry.getKey().outcomeId()))
                .toList();
    }

    /** Moves the row to its target era; a row already at the target violates the unique key, as it does in SQL. */
    @Override
    @Transactional
    public void confirm(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, int targetEraNumber) {
        var armed = new Row(gameId, eraNumber, eventId, outcomeId);
        rows.findForUpdate(armed).ifPresent(playerId -> {
            rows.remove(armed);
            rows.insert(new Row(gameId, targetEraNumber, eventId, outcomeId), playerId);
        });
    }

    @Override
    @Transactional
    public void delete(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        rows.remove(new Row(gameId, eraNumber, eventId, outcomeId));
    }

    @Override
    @Transactional
    public void deleteByGame(UUID gameId) {
        rows.entries(playerId -> true).stream()
                .map(java.util.Map.Entry::getKey)
                .filter(key -> key.gameId().equals(gameId))
                .forEach(rows::remove);
    }

    private record Row(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {}
}
