package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

class JpaFutureEventRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();
    private final EventStorePort eventStore = mock(EventStorePort.class);
    private final JpaFutureEventRepository repository = new JpaFutureEventRepository(
            eventStore,
            new EventStoreAppender(eventStore, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC)),
            objectMapper);

    private final UUID eventId = UUID.randomUUID();
    private final Outcome winner = new Outcome(UUID.randomUUID(), "winner", 100);

    @Test
    void outcomeAppliedStoredBeforeTheList_replaysWithAnEmptyList() {
        var template = """
                {"gameId":"%s","eraNumber":1,"eventId":"%s","winningOutcomeId":"%s",\
                "finalOutcomes":[{"outcomeId":"%s","description":"winner","probability":100,\
                "sealed":false,"annihilated":false}]}""";
        var winnerId = winner.outcomeId();
        var legacyPayload = template.formatted(UUID.randomUUID(), eventId, winnerId, winnerId);
        given(eventStore.readStream(eventId))
                .willReturn(List.of(
                        stored("FutureEventDrafted", serialized(new FutureEventDrafted(eventId, List.of(winner))), 0),
                        stored("OutcomeApplied", legacyPayload, 1)));

        var event = repository.findById(eventId);

        assertThat(event.resolved()).isTrue();
        assertThat(objectMapper.readValue(legacyPayload, OutcomeApplied.class).consequentialErasures())
                .isEmpty();
    }

    @Test
    void appendedOutcomeApplied_storesTheListItReplaysWith() {
        var erased = UUID.randomUUID();
        var applied =
                new OutcomeApplied(UUID.randomUUID(), 1, eventId, winner.outcomeId(), List.of(winner), List.of(erased));

        repository.append(eventId, applied);

        var captor = ArgumentCaptor.forClass(StoredEvent.class);
        verify(eventStore).append(captor.capture());
        assertThat(objectMapper.readValue(captor.getValue().payload(), OutcomeApplied.class))
                .isEqualTo(applied);
    }

    private String serialized(Object domainEvent) {
        return objectMapper.writeValueAsString(domainEvent);
    }

    private StoredEvent stored(String eventType, String payload, long sequenceNr) {
        return new StoredEvent(UUID.randomUUID(), eventId, "FutureEvent", eventType, 1, payload, NOW, sequenceNr);
    }
}
