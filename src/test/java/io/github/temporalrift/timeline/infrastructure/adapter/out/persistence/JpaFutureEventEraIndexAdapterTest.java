package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaFutureEventEraIndexAdapter.class})
class JpaFutureEventEraIndexAdapterTest {

    @Autowired
    FutureEventEraIndexPort eraIndex;

    @Test
    void findByGameIdAndEraNumber_returnsRowsInRevealIndexOrder_regardlessOfInsertionOrder() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var eventId0 = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();

        eraIndex.add(eventId2, gameId, eraNumber, 2);
        eraIndex.add(eventId0, gameId, eraNumber, 0);
        eraIndex.add(eventId1, gameId, eraNumber, 1);

        assertThat(eraIndex.findByGameIdAndEraNumber(gameId, eraNumber))
                .extracting(IndexedEventId::eventId)
                .containsExactly(eventId0, eventId1, eventId2);
    }
}
