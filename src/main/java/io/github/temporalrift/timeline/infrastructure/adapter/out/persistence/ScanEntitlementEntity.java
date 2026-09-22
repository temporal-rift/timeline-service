package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "scan_entitlement")
class ScanEntitlementEntity extends GameEraPlayerEventEntity {

    protected ScanEntitlementEntity() {
        // for JPA
    }

    ScanEntitlementEntity(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {
        super(gameId, eraNumber, playerId, eventId);
    }
}
