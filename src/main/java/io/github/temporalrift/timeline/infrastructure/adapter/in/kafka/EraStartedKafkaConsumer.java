package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.List;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code EraStarted} from {@code game.events}. Establishes that the era exists before
 * {@code EventsDrawn} arrives (design.md Decision 5), and persists this era's {@code playerIds} — the only
 * player roster available to {@code timeline-service}, needed by {@code ParadoxResolutionSagaImpl} to populate a
 * resolution phase's pending-player set (timeline-mvp8-paradox-completion design.md Decision 3). The payload is
 * deserialized to enforce the current {@code carryOverEventIds} contract shape (rejecting the retired
 * {@code cascadedEventIds} shape) even though those values go unused this slice.
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
