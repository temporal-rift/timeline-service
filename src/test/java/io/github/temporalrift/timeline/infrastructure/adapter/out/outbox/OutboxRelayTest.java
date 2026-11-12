package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Test
    @DisplayName("building the message fails (bad stored JSON) — reverts to PENDING, not stuck SENDING")
    void relay_messageBuildFails_revertsToPending() {
        var id = UUID.randomUUID();
        var row = OutboxEventEntity.pending(id, "timeline.events", "game-1", "not-json", "{}", Instant.now());
        given(repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)).willReturn(List.of(row));
        given(repository.compareAndSetStatus(id, OutboxStatus.PENDING, OutboxStatus.SENDING))
                .willReturn(1);

        relay.relay();

        then(kafkaTemplate).should(never()).send(any(Message.class));
        then(repository).should().compareAndSetStatus(id, OutboxStatus.SENDING, OutboxStatus.PENDING);
        then(repository).should(never()).compareAndSetStatus(id, OutboxStatus.SENDING, OutboxStatus.SENT);
    }

    @Test
    @DisplayName("a failed send is retried with the stored payload — consequential erasures are not recomputed")
    void relay_retryAfterFailedSend_republishesTheStoredPayloadUnchanged() {
        var id = UUID.randomUUID();
        var erased = UUID.randomUUID();
        var template = """
                {"gameId":"%s","eraNumber":1,"eventId":"%s","winningOutcomeId":"%s",\
                "finalProbabilities":[],"consequentialErasures":["%s"]}""";
        var payload = template.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), erased);
        var row = OutboxEventEntity.pending(id, "timeline.events", "game-1", "{}", payload, Instant.now());
        given(repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)).willReturn(List.of(row));
        given(repository.compareAndSetStatus(id, OutboxStatus.PENDING, OutboxStatus.SENDING))
                .willReturn(1);
        given(kafkaTemplate.send(any(Message.class)))
                .willThrow(new IllegalStateException("broker unavailable"))
                .willReturn(CompletableFuture.completedFuture(null));

        relay.relay();
        relay.relay();

        var sent = ArgumentCaptor.forClass(Message.class);
        then(kafkaTemplate).should(times(2)).send(sent.capture());
        Map<Object, Object> first =
                Map.copyOf((Map<?, ?>) sent.getAllValues().get(0).getPayload());
        Map<Object, Object> retried =
                Map.copyOf((Map<?, ?>) sent.getAllValues().get(1).getPayload());
        assertThat(retried).isEqualTo(first).containsEntry("consequentialErasures", List.of(erased.toString()));
        then(repository).should().compareAndSetStatus(id, OutboxStatus.SENDING, OutboxStatus.SENT);
    }

    @Test
    @DisplayName("an earlier row fails — sweep stops there, a later PENDING row is never attempted")
    void relay_earlierRowFails_stopsSweepBeforeLaterRow() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var first = OutboxEventEntity.pending(firstId, "timeline.events", "game-1", "not-json", "{}", Instant.now());
        var second = OutboxEventEntity.pending(secondId, "timeline.events", "game-1", "{}", "{}", Instant.now());
        given(repository.findByStatusOrderBySeqAsc(OutboxStatus.PENDING)).willReturn(List.of(first, second));
        given(repository.compareAndSetStatus(firstId, OutboxStatus.PENDING, OutboxStatus.SENDING))
                .willReturn(1);

        relay.relay();

        then(repository).should().compareAndSetStatus(firstId, OutboxStatus.SENDING, OutboxStatus.PENDING);
        then(repository).should(never()).compareAndSetStatus(secondId, OutboxStatus.PENDING, OutboxStatus.SENDING);
        then(kafkaTemplate).should(never()).send(any(Message.class));
    }
}
