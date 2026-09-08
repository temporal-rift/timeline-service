package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort.ScanEntitlement;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaScanEntitlementAdapter.class})
class JpaScanEntitlementAdapterTest {

    @Autowired
    ScanEntitlementPort scanEntitlements;

    @Test
    void upsert_thenFindByGameAndEra_returnsTheEntitlement() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();

        scanEntitlements.upsert(gameId, 1, playerId, eventId);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1))
                .containsExactly(new ScanEntitlement(playerId, eventId));
    }

    @Test
    void upsert_repeatedForSamePlayerAndEvent_isIdempotent() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();

        scanEntitlements.upsert(gameId, 1, playerId, eventId);
        scanEntitlements.upsert(gameId, 1, playerId, eventId);
        scanEntitlements.upsert(gameId, 1, playerId, eventId);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1)).hasSize(1);
    }

    @Test
    void upsert_differentPlayersOrEvents_areIndependentRows() {
        var gameId = UUID.randomUUID();
        var playerA = UUID.randomUUID();
        var playerB = UUID.randomUUID();
        var eventA = UUID.randomUUID();
        var eventB = UUID.randomUUID();

        scanEntitlements.upsert(gameId, 1, playerA, eventA);
        scanEntitlements.upsert(gameId, 1, playerA, eventB);
        scanEntitlements.upsert(gameId, 1, playerB, eventA);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1))
                .containsExactlyInAnyOrder(
                        new ScanEntitlement(playerA, eventA),
                        new ScanEntitlement(playerA, eventB),
                        new ScanEntitlement(playerB, eventA));
    }

    @Test
    void findByGameAndEra_doesNotReturnAnotherEraOfTheSameGame() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var era1EventId = UUID.randomUUID();
        var era2EventId = UUID.randomUUID();
        scanEntitlements.upsert(gameId, 1, playerId, era1EventId);
        scanEntitlements.upsert(gameId, 2, playerId, era2EventId);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1))
                .containsExactly(new ScanEntitlement(playerId, era1EventId));
        assertThat(scanEntitlements.findByGameAndEra(gameId, 2))
                .containsExactly(new ScanEntitlement(playerId, era2EventId));
    }

    @Test
    void deleteByGameAndEra_removesOnlyThatGameAndEra() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var era1EventId = UUID.randomUUID();
        var era2EventId = UUID.randomUUID();
        scanEntitlements.upsert(gameId, 1, playerId, era1EventId);
        scanEntitlements.upsert(gameId, 2, playerId, era2EventId);

        scanEntitlements.deleteByGameAndEra(gameId, 1);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(scanEntitlements.findByGameAndEra(gameId, 2))
                .containsExactly(new ScanEntitlement(playerId, era2EventId));
    }

    @Test
    void deleteByGameAndEra_noMatchingRows_isHarmless() {
        // No exception is the assertion; JUnit fails the test if deleteByGameAndEra throws.
        scanEntitlements.deleteByGameAndEra(UUID.randomUUID(), 1);
    }

    @Test
    void deleteByGame_removesEveryEraForThatGame() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        scanEntitlements.upsert(gameId, 1, playerId, UUID.randomUUID());
        scanEntitlements.upsert(gameId, 2, playerId, UUID.randomUUID());

        scanEntitlements.deleteByGame(gameId);

        assertThat(scanEntitlements.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(scanEntitlements.findByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    void deleteByGame_doesNotAffectAnotherGame() {
        var gameId1 = UUID.randomUUID();
        var gameId2 = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        scanEntitlements.upsert(gameId1, 1, playerId, eventId);
        scanEntitlements.upsert(gameId2, 1, playerId, eventId);

        scanEntitlements.deleteByGame(gameId1);

        assertThat(scanEntitlements.findByGameAndEra(gameId1, 1)).isEmpty();
        assertThat(scanEntitlements.findByGameAndEra(gameId2, 1))
                .containsExactly(new ScanEntitlement(playerId, eventId));
    }
}
