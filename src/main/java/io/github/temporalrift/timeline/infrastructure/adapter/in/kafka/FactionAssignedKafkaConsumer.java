package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Objects;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.FactionAssignedPayload;
import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code FactionAssigned} from {@code game.events}. Persists each player's faction for its
 * game — the only participation and faction source available to {@code timeline-service}, needed by
 * the gated chains endpoint to tell participants from outsiders and Weavers from other factions.
 */
@Component
class FactionAssignedKafkaConsumer {

    private static final String CONSUMER = "membership.faction-assigned";
    private static final GameEventIngestion.Spec SPEC =
            new GameEventIngestion.Spec("FactionAssigned", CONSUMER, 1, true);

    private final ProcessedEventPort processedEvents;
    private final GameMembershipPort memberships;
    private final ObjectMapper objectMapper;
    private final GameEventSkipMetrics skipMetrics;

    FactionAssignedKafkaConsumer(
            ProcessedEventPort processedEvents,
            GameMembershipPort memberships,
            ObjectMapper objectMapper,
            GameEventSkipMetrics skipMetrics) {
        this.processedEvents = processedEvents;
        this.memberships = memberships;
        this.objectMapper = objectMapper;
        this.skipMetrics = skipMetrics;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents, skipMetrics).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), FactionAssignedPayload.class);
            Objects.requireNonNull(payload.gameId(), "gameId");
            Objects.requireNonNull(payload.playerId(), "playerId");
            Objects.requireNonNull(payload.faction(), "faction");
            memberships.save(new GameMembership(
                    payload.gameId(),
                    payload.playerId(),
                    MemberFaction.valueOf(payload.faction().name())));
        });
    }
}
