package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import tools.jackson.databind.ObjectMapper;

/**
 * Reads a {@code Message<Object>} payload into a target record type.
 *
 * <p>With a raw {@code Message<Object>} listener parameter, Spring Kafka's message converter has no
 * concrete target type to convert the body to, so {@code message.getPayload()} stays the undeserialized
 * {@code byte[]} record value at runtime — {@code ObjectMapper#convertValue} on a {@code byte[]} source
 * treats it as base64-encoded binary, not JSON text, and fails. Real records need {@code readValue};
 * already-typed test payloads (built directly as a record via {@code MessageBuilder}) need
 * {@code convertValue}.
 */
final class GameEventPayloads {

    private GameEventPayloads() {}

    static <T> T read(ObjectMapper objectMapper, Object payload, Class<T> type) {
        return payload instanceof byte[] bytes
                ? objectMapper.readValue(bytes, type)
                : objectMapper.convertValue(payload, type);
    }
}
