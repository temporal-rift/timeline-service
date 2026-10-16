package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.util.LinkedHashMap;

import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;

/** The outbox row shape shared by the JPA and the in-memory outbox, so both persist the same bytes. */
public final class OutboxMessages {

    public static final String TIMELINE_EVENTS_TOPIC = "timeline.events";

    private static final String SCS_DESTINATION_HEADER = "spring.cloud.stream.sendto.destination";

    private OutboxMessages() {}

    /** Whether a generated producer stamped the message for externalization. */
    public static boolean isExternalized(Message<?> message) {
        return message.getHeaders().get(SCS_DESTINATION_HEADER, String.class) != null;
    }

    public static Serialized serialize(ObjectMapper objectMapper, Message<?> message) {
        var headers = message.getHeaders();
        return new Serialized(
                headers.get("gameId", String.class),
                objectMapper.writeValueAsString(new LinkedHashMap<>(headers)),
                objectMapper.writeValueAsString(message.getPayload()));
    }

    public record Serialized(String messageKey, String headers, String payload) {}
}
