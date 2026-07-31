package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Periodic sweep that relays {@code PENDING} {@code outbox_events} rows to Kafka, one instance-local
 * sweep per {@code game.timers}-style interval (developer-notes.md §5 "Timer model"). Never publishes to
 * Kafka inside the same transaction as the claim or the mark-sent step — each is its own short,
 * independently-committed transaction (on {@link OutboxEventJpaRepository#compareAndSetStatus}, not here
 * — {@code @Transactional} on this class's own methods would be silently ineffective for calls made from
 * {@link #relay()} via self-invocation, since that bypasses Spring's proxy-based transaction advice),
 * with the actual send happening between them.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventJpaRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    OutboxRelay(
            OutboxEventJpaRepository repository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000)
    void relay() {
        for (var row : repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)) {
            if (repository.compareAndSetStatus(row.id(), OutboxStatus.PENDING, OutboxStatus.SENDING) == 1) {
                if (send(row)) {
                    repository.compareAndSetStatus(row.id(), OutboxStatus.SENDING, OutboxStatus.SENT);
                } else {
                    // Revert so the next sweep retries, instead of leaving the row stuck SENDING on a
                    // send failure Kafka itself reported (as opposed to a relay crash mid-flight, which
                    // is the still-accepted stuck-row risk in design.md).
                    repository.compareAndSetStatus(row.id(), OutboxStatus.SENDING, OutboxStatus.PENDING);
                }
            }
        }
    }

    /** @return true if Kafka confirmed the send within the wait window. */
    private boolean send(OutboxEventEntity row) {
        var headers = objectMapper.readValue(row.headers(), Map.class);
        var payload = objectMapper.readValue(row.payload(), Object.class);
        var message = MessageBuilder.withPayload(payload)
                .copyHeaders(headers)
                .setHeader(KafkaHeaders.TOPIC, row.topic())
                .setHeader(KafkaHeaders.KEY, row.messageKey())
                .build();
        try {
            kafkaTemplate.send(message).get(10, TimeUnit.SECONDS);
            log.debug("Relayed outbox event {} to {}", row.id(), row.topic());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted relaying outbox event {} to {} — will retry next sweep", row.id(), row.topic());
            return false;
        } catch (Exception e) {
            log.warn("Failed to relay outbox event {} to {} — will retry next sweep", row.id(), row.topic(), e);
            return false;
        }
    }
}
