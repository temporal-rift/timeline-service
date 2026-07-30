package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxEventListenerTest {

    @Mock
    OutboxEventJpaRepository repository;

    @Spy
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    OutboxEventListener listener;

    @Test
    @DisplayName("message carrying the SCS destination header — persists one PENDING row")
    void onMessage_withDestinationHeader_persistsRow() {
        var message = MessageBuilder.withPayload(Map.of("gameId", "irrelevant-to-this-test"))
                .setHeader("gameId", "a-game-id")
                .setHeader("spring.cloud.stream.sendto.destination", "Timelinepublish-outcome-applied-out")
                .build();

        listener.onMessage(message);

        then(repository).should().save(any(OutboxEventEntity.class));
    }

    @Test
    @DisplayName("message without the SCS destination header — ignored")
    void onMessage_withoutDestinationHeader_ignored() {
        var message = MessageBuilder.withPayload(Map.of("gameId", "irrelevant")).build();

        listener.onMessage(message);

        then(repository).should(never()).save(any());
    }
}
