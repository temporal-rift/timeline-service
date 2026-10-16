package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The in-memory {@code outbox_events} table. Rows get their relay order from a sequence taken when the row is
 * inserted, not when it commits, exactly as the database sequence does, and only committed rows are visible to the
 * relay.
 */
@Component
@Profile("in-memory")
class InMemoryOutboxStore {

    private final InMemoryDatabase database;
    private final Table<Long, OutboxRow> rows;
    private final AtomicLong sequence = new AtomicLong();

    InMemoryOutboxStore(InMemoryDatabase database) {
        this.database = database;
        this.rows = database.table(TableNames.OUTBOX_EVENTS);
    }

    void append(String topic, String messageKey, String headers, String payload, Instant createdAt) {
        var seq = sequence.incrementAndGet();
        rows.insert(
                seq,
                new OutboxRow(seq, UUID.randomUUID(), topic, messageKey, headers, payload, State.PENDING, createdAt));
    }

    /** Committed rows still waiting to be relayed, in relay order. */
    List<OutboxRow> pending() {
        return rows.list(row -> row.state() == State.PENDING).stream()
                .sorted(Comparator.comparingLong(OutboxRow::seq))
                .toList();
    }

    /** Compare-and-set on the row state: only the caller that flips {@code from} to {@code to} may act on the row. */
    boolean transition(long seq, State from, State to) {
        return database.inTransaction(() -> rows.findForUpdate(seq)
                .filter(row -> row.state() == from)
                .map(row -> {
                    rows.put(seq, row.withState(to));
                    return true;
                })
                .orElse(false));
    }

    /** Rows accepted for publication that the relay has not yet delivered. */
    int unsent() {
        return rows.list(row -> row.state() != State.SENT).size();
    }

    enum State {
        PENDING,
        SENDING,
        SENT
    }

    record OutboxRow(
            long seq,
            UUID id,
            String topic,
            String messageKey,
            String headers,
            String payload,
            State state,
            Instant createdAt) {

        OutboxRow withState(State next) {
            return new OutboxRow(seq, id, topic, messageKey, headers, payload, next, createdAt);
        }
    }
}
