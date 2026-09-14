package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.List;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EraStartedPayload;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code EraStarted} from {@code game.events}, creating the era before {@code EventsDrawn} arrives and
 * persisting the player roster that lets a resolution phase determine when all players have submitted.
 */
@Component
class EraStartedKafkaConsumer {

    private static final String CONSUMER = "futureevent.era-started";
    private static final GameEventIngestion.Spec SPEC = new GameEventIngestion.Spec("EraStarted", CONSUMER, 1);

    private final ProcessedEventPort processedEvents;
    private final EraPlayersPort eraPlayers;
    private final ObjectMapper objectMapper;

    EraStartedKafkaConsumer(ProcessedEventPort processedEvents, EraPlayersPort eraPlayers, ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.eraPlayers = eraPlayers;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), EraStartedPayload.class);
            var playerIds = payload.playerIds() == null ? List.<UUID>of() : payload.playerIds();
            eraPlayers.save(payload.gameId(), payload.eraNumber(), playerIds);
        });
    }
}
