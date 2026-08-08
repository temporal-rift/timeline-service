package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "round_amplify_pending")
class RoundAmplifyPendingEntity extends RoundScopedEntity {

    @Column(name = "pending", nullable = false)
    private boolean pending;

    protected RoundAmplifyPendingEntity() {
        // for JPA
    }

    RoundAmplifyPendingEntity(RoundKey key, boolean pending) {
        super(key);
        this.pending = pending;
    }

    boolean pending() {
        return pending;
    }
}
