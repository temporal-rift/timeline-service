package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    OutboxEventJpaRepository repository;

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @InjectMocks
    OutboxRelay relay;

    @Test
    @DisplayName("claim succeeds — sends to Kafka and marks SENT")
    void relay_claimSucceeds_sendsAndMarksSent() {
        var id = UUID.randomUUID();
        var row = OutboxEventEntity.pending(id, "timeline.events", "game-1", "{}", "{}", Instant.now());
        given(repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)).willReturn(List.of(row));
        given(repository.compareAndSetStatus(id, OutboxStatus.PENDING, OutboxStatus.SENDING))
                .willReturn(1);

        relay.relay();

        then(kafkaTemplate).should().send(any(Message.class));
        then(repository).should().compareAndSetStatus(id, OutboxStatus.SENDING, OutboxStatus.SENT);
    }

    @Test
    @DisplayName("claim fails (already claimed elsewhere) — never sends")
    void relay_claimFails_doesNotSend() {
        var id = UUID.randomUUID();
        var row = OutboxEventEntity.pending(id, "timeline.events", "game-1", "{}", "{}", Instant.now());
        given(repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)).willReturn(List.of(row));
        given(repository.compareAndSetStatus(id, OutboxStatus.PENDING, OutboxStatus.SENDING))
                .willReturn(0);

        relay.relay();

        then(kafkaTemplate).should(never()).send(any(Message.class));
        then(repository).should(never()).compareAndSetStatus(id, OutboxStatus.SENDING, OutboxStatus.SENT);
    }
}
