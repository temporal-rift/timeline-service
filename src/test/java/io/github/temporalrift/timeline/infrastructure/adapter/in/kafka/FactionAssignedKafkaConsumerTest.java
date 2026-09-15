package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.Faction;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.FactionAssignedPayload;
import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

@ExtendWith(MockitoExtension.class)
class FactionAssignedKafkaConsumerTest {

    private static final String EVENT_TYPE = "FactionAssigned";
    private static final String CONSUMER = "membership.faction-assigned";

    @Mock
    ProcessedEventPort processedEvents;

    @Mock
    GameEventSkipMetrics skipMetrics;

    @Mock
    GameMembershipPort memberships;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    FactionAssignedKafkaConsumer consumer;

    @Test
    @DisplayName("matching event type — claims the eventId")
    void handle_matchingEventType_claimsEventId() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var payload = new FactionAssignedPayload(UUID.randomUUID(), UUID.randomUUID(), Faction.WEAVERS);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
    }

    @Test
    @DisplayName("matching event type — persists the player's faction for the game")
    void handle_matchingEventType_persistsMembership() {
        var eventId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(true);
        var payload = new FactionAssignedPayload(gameId, playerId, Faction.WEAVERS);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        var captor = ArgumentCaptor.forClass(GameMembership.class);
        then(memberships).should().save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));
    }

    @Test
    @DisplayName("unrelated event type — ignored, never claims")
    void handle_unrelatedEventType_ignored() {
        consumer.handle(KafkaTestMessages.withHeaders(
                new FactionAssignedPayload(UUID.randomUUID(), UUID.randomUUID(), Faction.ERASERS),
                UUID.randomUUID(),
                "GameStarted",
                1));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("unsupported version — skipped without claiming")
    void handle_unsupportedVersion_skippedWithoutClaim() {
        var payload = new FactionAssignedPayload(UUID.randomUUID(), UUID.randomUUID(), Faction.PROPHETS);

        consumer.handle(KafkaTestMessages.withHeaders(payload, UUID.randomUUID(), EVENT_TYPE, 2));

        then(processedEvents).should(never()).claim(any(), any());
    }

    @Test
    @DisplayName("duplicate eventId — claim returns false, nothing else happens")
    void handle_duplicateEventId_ignored() {
        var eventId = UUID.randomUUID();
        given(processedEvents.claim(eventId, CONSUMER)).willReturn(false);
        var payload = new FactionAssignedPayload(UUID.randomUUID(), UUID.randomUUID(), Faction.ACTIVISTS);

        consumer.handle(KafkaTestMessages.withHeaders(payload, eventId, EVENT_TYPE, 1));

        then(processedEvents).should().claim(eventId, CONSUMER);
        then(memberships).should(never()).save(any());
    }
}
