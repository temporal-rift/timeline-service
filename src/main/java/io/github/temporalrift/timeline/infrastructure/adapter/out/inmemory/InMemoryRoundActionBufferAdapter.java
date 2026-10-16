package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;

@Component
@Profile("in-memory")
class InMemoryRoundActionBufferAdapter implements RoundActionBufferPort {

    private final Table<UUID, BufferedRow> rows;

    InMemoryRoundActionBufferAdapter(InMemoryDatabase database) {
        this.rows = database.table(TableNames.ROUND_ACTION_BUFFER);
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, BufferedAction action) {
        rows.insert(UUID.randomUUID(), new BufferedRow(gameId, eraNumber, roundNumber, action));
    }

    @Override
    public List<BufferedAction> findByRound(UUID gameId, int eraNumber, int roundNumber) {
        return rows
                .list(row ->
                        row.gameId().equals(gameId) && row.eraNumber() == eraNumber && row.roundNumber() == roundNumber)
                .stream()
                .map(BufferedRow::action)
                .toList();
    }

    private record BufferedRow(UUID gameId, int eraNumber, int roundNumber, BufferedAction action) {}
}
