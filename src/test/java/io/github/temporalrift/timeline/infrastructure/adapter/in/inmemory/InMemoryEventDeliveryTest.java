package io.github.temporalrift.timeline.infrastructure.adapter.in.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;

import io.github.temporalrift.timeline.domain.execution.SourceWatermark;

class InMemoryEventDeliveryTest {

    private final List<String> started = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private AnnotationConfigApplicationContext context;
    private InMemoryEventDelivery delivery;

    @BeforeEach
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(Listeners.class, () -> new Listeners(started, releaseFirst));
        context.refresh();
        delivery = new InMemoryEventDelivery(context);
    }

    @AfterEach
    void stopContext() {
        context.close();
    }

    @Test
    @DisplayName("runs every listener subscribed to the topic once, in group order, with the headers and raw bytes")
    void runsEachSubscribedGroupOnce() {
        delivery.accept(record("game.events", "g1", "first"));

        assertThat(started).containsExactly("a-group:first", "b-group:first");
    }

    @Test
    @DisplayName("ignores a record for a topic nobody consumes")
    void ignoresForeignTopic() {
        delivery.accept(record("other.events", "g1", "first"));

        assertThat(started).isEmpty();
    }

    @Test
    @DisplayName("a second record of the same key starts only after the first one's handler has finished")
    void sameKey_waitsForTheEarlierRecord() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    CompletableFuture.runAsync(() -> delivery.accept(record("game.events", "g1", "block")), executor);
            while (!started.contains("a-group:block")) {
                Thread.onSpinWait();
            }
            var second =
                    CompletableFuture.runAsync(() -> delivery.accept(record("game.events", "g1", "next")), executor);

            Thread.sleep(300);
            assertThat(started).doesNotContain("a-group:next");

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(started).containsSubsequence("b-group:block", "a-group:next");
        }
    }

    @Test
    @DisplayName("records of different keys do not wait for each other")
    void differentKeys_runConcurrently() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    CompletableFuture.runAsync(() -> delivery.accept(record("game.events", "g1", "block")), executor);
            while (!started.contains("a-group:block")) {
                Thread.onSpinWait();
            }

            delivery.accept(record("game.events", "g2", "other"));

            assertThat(started).contains("b-group:other");
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a failing group does not stop the others, and the failure reaches the caller")
    void failure_runsTheOtherGroupsAndPropagates() {
        assertThatThrownBy(() -> delivery.accept(record("game.events", "g1", "fail")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a-group failed");

        assertThat(started).contains("b-group:fail");
        assertThat(delivery.watermarks())
                .extracting(w -> w.groupId() + "|" + w.topic() + "|" + w.partition() + "|" + w.nextOffset())
                .containsExactly("a-group|game.events|0|0", "b-group|game.events|0|1");
    }

    @Test
    @DisplayName("rejects a record missing its topic")
    void rejectsMalformedRecord() {
        assertThatThrownBy(() -> delivery.accept(Map.of("key", "g1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    @DisplayName("lists a group's watermark per consumed topic before anything is delivered")
    void watermarks_startAtZero() {
        assertThat(delivery.watermarks())
                .containsExactly(
                        new SourceWatermark("a-group", "game.events", 0, 0),
                        new SourceWatermark("b-group", "game.events", 0, 0));
    }

    private static Map<String, Object> record(String topic, String key, String body) {
        return Map.of("topic", topic, "key", key, "headers", Map.of("eventType", "Any"), "payload", body.getBytes());
    }

    static class Listeners {

        private final List<String> started;
        private final CountDownLatch releaseFirst;

        Listeners(List<String> started, CountDownLatch releaseFirst) {
            this.started = started;
            this.releaseFirst = releaseFirst;
        }

        @KafkaListener(topics = "game.events", groupId = "a-group")
        public void groupA(Message<Object> message) throws InterruptedException {
            var body = new String((byte[]) message.getPayload());
            assertThat(message.getHeaders().get("eventType", String.class)).isEqualTo("Any");
            started.add("a-group:" + body);
            if (body.equals("block")) {
                releaseFirst.await(10, TimeUnit.SECONDS);
            }
            if (body.equals("fail")) {
                throw new IllegalStateException("a-group failed");
            }
        }

        @KafkaListener(topics = "game.events", groupId = "b-group")
        public void groupB(Message<Object> message) {
            started.add("b-group:" + new String((byte[]) message.getPayload()));
        }
    }
}
