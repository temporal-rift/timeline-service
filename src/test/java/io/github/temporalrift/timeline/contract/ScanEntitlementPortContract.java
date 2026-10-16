package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort.ScanEntitlement;

public interface ScanEntitlementPortContract {

    ScanEntitlementPort scanEntitlements();

    @Test
    @DisplayName("scan entitlements: upserting the same entitlement again changes nothing")
    default void scanEntitlements_upsertIsIdempotent() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();

        scanEntitlements().upsert(gameId, 1, playerId, eventId);
        scanEntitlements().upsert(gameId, 1, playerId, eventId);
        scanEntitlements().upsert(gameId, 2, playerId, eventId);

        assertThat(scanEntitlements().findByGameAndEra(gameId, 1))
                .containsExactly(new ScanEntitlement(playerId, eventId));
        assertThat(scanEntitlements().findByGameAndEra(gameId, 2)).hasSize(1);
        assertThat(scanEntitlements().findByGameAndEra(UUID.randomUUID(), 1)).isEmpty();
    }

    @Test
    @DisplayName("scan entitlements: deleting by era or by game removes only that scope and tolerates none")
    default void scanEntitlements_deleteIsScoped() {
        var gameId = UUID.randomUUID();
        var otherGame = UUID.randomUUID();
        scanEntitlements().upsert(gameId, 1, UUID.randomUUID(), UUID.randomUUID());
        scanEntitlements().upsert(gameId, 2, UUID.randomUUID(), UUID.randomUUID());
        scanEntitlements().upsert(otherGame, 1, UUID.randomUUID(), UUID.randomUUID());

        scanEntitlements().deleteByGameAndEra(gameId, 1);
        scanEntitlements().deleteByGameAndEra(gameId, 7);

        assertThat(scanEntitlements().findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(scanEntitlements().findByGameAndEra(gameId, 2)).hasSize(1);

        scanEntitlements().deleteByGame(gameId);
        scanEntitlements().deleteByGame(UUID.randomUUID());

        assertThat(scanEntitlements().findByGameAndEra(gameId, 2)).isEmpty();
        assertThat(scanEntitlements().findByGameAndEra(otherGame, 1)).hasSize(1);
    }
}
