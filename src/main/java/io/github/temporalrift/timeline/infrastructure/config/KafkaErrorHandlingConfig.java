package io.github.temporalrift.timeline.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaErrorHandlingConfig {

    static final String DEAD_LETTER_TOPIC = "game.dlq";
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 2L;

    @Bean
    NewTopic gameDeadLetterTopic() {
        return new NewTopic(DEAD_LETTER_TOPIC, 3, (short) 1);
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaOperations<Object, Object> kafkaOperations) {
        return new DeadLetterPublishingRecoverer(
                kafkaOperations, (consumerRecord, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }
}
