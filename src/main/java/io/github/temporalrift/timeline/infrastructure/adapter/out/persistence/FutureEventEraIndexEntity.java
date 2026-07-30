package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "future_event_era_index")
class FutureEventEraIndexEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    protected FutureEventEraIndexEntity() {
        // for JPA
    }

    FutureEventEraIndexEntity(UUID eventId, UUID gameId, int eraNumber) {
        this.eventId = eventId;
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.resolved = false;
    }

    UUID eventId() {
        return eventId;
    }

    void markResolved() {
        this.resolved = true;
    }
}
