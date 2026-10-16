package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

public interface ProcessedEventPortContract extends TransactionalContract {

    ProcessedEventPort processedEvents();

    @Test
    @DisplayName("processed events: a duplicate claim reports already claimed, other consumers claim independently")
    default void processedEvents_duplicateClaimIsRefused() {
        var eventId = UUID.fromString("7b0c1f2e-4a5d-4c3b-9e8f-0a1b2c3d4e5f");
        var consumer = "contract." + UUID.randomUUID();

        assertThat(processedEvents().claim(eventId, consumer)).isTrue();
        assertThat(processedEvents().claim(eventId, consumer)).isFalse();
        assertThat(processedEvents().claim(eventId, consumer + ".other")).isTrue();
        assertThat(processedEvents().claim(UUID.randomUUID(), consumer)).isTrue();
    }

    @Test
    @DisplayName("processed events: a claim made by a transaction that rolls back is released")
    default void processedEvents_rolledBackClaimIsReleased() {
        var eventId = UUID.randomUUID();
        var consumer = "contract." + UUID.randomUUID();

        assertThatThrownBy(() -> transactions().executeWithoutResult(status -> {
                    assertThat(processedEvents().claim(eventId, consumer)).isTrue();
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");

        assertThat(processedEvents().claim(eventId, consumer)).isTrue();
    }

    @Test
    @DisplayName("processed events: a concurrent claim waits for the first transaction and then finds it taken")
    default void processedEvents_concurrentClaimWaitsThenLoses() throws Exception {
        var eventId = UUID.randomUUID();
        var consumer = "contract." + UUID.randomUUID();
        var held = holdTransaction(() -> processedEvents().claim(eventId, consumer));

        var second = CompletableFuture.supplyAsync(() -> processedEvents().claim(eventId, consumer));

        Thread.sleep(300);
        assertThat(second).isNotDone();
        assertThat(held.commit()).isTrue();
        assertThat(second.get(30, TimeUnit.SECONDS)).isFalse();
    }
}
