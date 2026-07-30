package io.github.temporalrift.timeline.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.JsonKafkaHeaderMapper;
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka consumer message conversion for {@code game.events}.
 *
 * <p>Consumers deserialize the raw record value with {@code ByteArrayDeserializer} and rely on a
 * {@link ByteArrayJacksonJsonMessageConverter} to turn the JSON bytes into each {@code @KafkaListener}
 * method's parameter type, while the real per-message Kafka headers (not a body field) carry the
 * envelope metadata — see {@code GameEventEnvelope}. {@code occurredAt} travels as a non-String
 * ({@code Instant}) header, which the header mapper only reconstructs for packages it trusts —
 * {@code java.time} is not trusted by default.
 *
 * <p>{@link org.springframework.kafka.support.converter.MessagingMessageConverter} (the converter's
 * superclass) builds its own default {@link JsonKafkaHeaderMapper} internally unless one is explicitly
 * set via {@code setHeaderMapper} — a separately declared {@code KafkaHeaderMapper} bean is never wired
 * in on its own, so the trusted-package configuration must be set directly on this converter.
 */
@Configuration
class KafkaConsumerConfig {

    @Bean
    ByteArrayJacksonJsonMessageConverter kafkaMessageConverter(JsonMapper jsonMapper) {
        var converter = new ByteArrayJacksonJsonMessageConverter(jsonMapper);
        var headerMapper = new JsonKafkaHeaderMapper();
        // Exact package-name match, no wildcard support (JsonKafkaHeaderMapper#trusted does
        // packageName.equals(trustedPackage)) — "java.time.*" silently never matches.
        headerMapper.addTrustedPackages("java.time");
        converter.setHeaderMapper(headerMapper);
        return converter;
    }
}
