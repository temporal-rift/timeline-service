package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.execution.EntropyDerivation;
import io.github.temporalrift.timeline.domain.execution.EntropyPurpose;
import io.github.temporalrift.timeline.domain.execution.EntropyVersion;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.IdentityKind;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SimulationSeat;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;

/**
 * An isolated simulation deployment resolves from the configured seed and lets only logical time expire a paradox
 * phase: the real timeout handler runs once when the clock crosses the deadline, and the checkpoint drains.
 */
@SpringBootTest(properties = "game.simulation.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class, GameEventsTestPublisher.class})
class SimulationExecutionIT {

    private static final UUID CASE_KEY = UUID.fromString("c0ffee00-0000-4000-8000-000000000189");
    private static final String SEED = "42";
    private static final String DIGEST = "b".repeat(64);
    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID PLAYER_0 = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID PLAYER_1 = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID PLAYER_2 = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            CASE_KEY,
            new Seed(SEED),
            EntropyVersion.SHA256_V1,
            DIGEST,
            START,
            List.of(
                    new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                    new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                    new SimulationSeat(2, PLAYER_2, MemberFaction.WEAVERS)));

    private static final String EXECUTION_PATH = "/internal/simulation/v1/execution";
    private static final String CHECKPOINT_PATH = "/internal/simulation/v1/checkpoint";
    private static final String CLOCK_PATH = "/internal/simulation/v1/clock";
    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GameEventsTestPublisher gameEvents;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void seededCase_resolvesReproduciblyAndOnlyLogicalTimeExpiresTheParadoxPhase() throws Exception {
        var gameId = UUID.randomUUID();
        var paradoxedEventId = UUID.randomUUID();
        var tiedA = UUID.randomUUID();
        var tiedB = UUID.randomUUID();
        var trailing = UUID.randomUUID();
        var cleanEventId = UUID.randomUUID();
        var cleanOutcomes = List.of(
                new Outcome(UUID.randomUUID(), "a", 45),
                new Outcome(UUID.randomUUID(), "b", 35),
                new Outcome(UUID.randomUUID(), "c", 20));

        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.state").value("READY"));

        gameEvents.eraStarted(gameId, 1, List.of(PLAYER_0, PLAYER_1, PLAYER_2));
        publishEventsDrawn(gameId, paradoxedEventId, tiedA, tiedB, trailing, cleanEventId, cleanOutcomes);
        await().atMost(Duration.ofSeconds(30)).until(() -> indexedEvents(gameId) == 2);
        gameEvents.cardPlayed(gameId, 1, paradoxedEventId, "COLLIDE", tiedA, tiedB);
        gameEvents.actionRoundClosed(gameId, 1, 1);
        var resolutionStarted = UUID.randomUUID();
        gameEvents.resolutionStarted(gameId, 1, resolutionStarted);

        await().atMost(Duration.ofSeconds(30)).until(() -> count(gameId, PARADOX_DETECTED) == 1);
        var paradoxId = paradoxIds(gameId).getFirst();
        assertThat(paradoxId)
                .isEqualTo(EntropyDerivation.identity(
                        CONTEXT,
                        IdentityKind.ERA_RESOLUTION_PARADOX.name(),
                        "era=1;slot=0;subject=" + paradoxedEventId));
        var winner = winnerOf(gameId, cleanEventId);
        assertThat(winner).isEqualTo(expectedWinner(cleanEventId, cleanOutcomes));

        // The test profile's 2s phase timer and 1s sweep would long have fired on wall time.
        await().during(Duration.ofSeconds(4))
                .atMost(Duration.ofSeconds(6))
                .until(() -> count(gameId, PARADOX_CASCADED) == 0);
        mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextDeadline").value("2026-03-01T10:00:02Z"))
                .andExpect(jsonPath("$.dueTimersPending").value(0));

        var operation = UUID.randomUUID();
        var deadlineAdvance = clockBody(operation, 0, "2026-03-01T10:00:02Z");
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineAdvance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedRevision").value(1));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> count(gameId, PARADOX_CASCADED) == 1 && count(gameId, ERA_RESOLUTION_COMPLETED) == 1);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                        .andExpect(jsonPath("$.drained").value(true))
                        .andExpect(jsonPath("$.dueTimersPending").value(0))
                        .andExpect(jsonPath("$.revision").value(1)));

        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deadlineAdvance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedRevision").value(1));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(UUID.randomUUID(), 0, "2026-03-01T10:00:03Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_EXECUTION_REVISION"));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(UUID.randomUUID(), 1, "2026-03-01T10:00:01Z")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLOCK_REGRESSION"));

        // The same resolution input again, as a fresh record: the drawn outcome stands and nothing republishes.
        gameEvents.resolutionStarted(gameId, 1, UUID.randomUUID());
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .until(() -> count(gameId, PARADOX_CASCADED) == 1
                        && count(gameId, OUTCOME_APPLIED) == 1
                        && count(gameId, ERA_RESOLUTION_COMPLETED) == 1);
        assertThat(winnerOf(gameId, cleanEventId)).isEqualTo(winner);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM simulation_entropy_decision WHERE coordinate = ?",
                        Integer.class,
                        "era=1;subject=" + cleanEventId))
                .isPositive();
    }

    /** Draws the clean event's winner the way the seeded execution must, from the case seed alone. */
    private static UUID expectedWinner(UUID eventId, List<Outcome> outcomes) {
        var purpose = EntropyPurpose.OUTCOME_RESOLUTION.name();
        var coordinate = "era=1;subject=" + eventId;
        var draw = new RandomGenerator() {
            private int index;

            @Override
            public long nextLong() {
                return EntropyDerivation.word(CONTEXT, purpose, coordinate, index++);
            }
        };
        var event = FutureEvent.replay(eventId, List.of(new FutureEventDrafted(eventId, outcomes)));
        return event.resolve(UUID.randomUUID(), 1, draw).winningOutcomeId();
    }

    private void publishEventsDrawn(
            UUID gameId,
            UUID paradoxedEventId,
            UUID tiedA,
            UUID tiedB,
            UUID trailing,
            UUID cleanEventId,
            List<Outcome> cleanOutcomes) {
        gameEvents.publish(
                gameId,
                "EventsDrawn",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        1,
                        "events",
                        List.of(
                                drawnEvent(
                                        paradoxedEventId,
                                        List.of(
                                                new Outcome(tiedA, "a", 45),
                                                new Outcome(tiedB, "b", 35),
                                                new Outcome(trailing, "c", 20))),
                                drawnEvent(cleanEventId, cleanOutcomes))));
    }

    private static Map<String, Object> drawnEvent(UUID eventId, List<Outcome> outcomes) {
        return Map.of(
                "eventId",
                eventId,
                "title",
                "Simulated Future Event",
                "carryOverState",
                "FRESH",
                "outcomes",
                outcomes.stream()
                        .map(o -> Map.of(
                                "outcomeId",
                                o.outcomeId(),
                                "description",
                                o.description(),
                                "initialProbability",
                                o.probability()))
                        .toList());
    }

    private int indexedEvents(UUID gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM future_event_era_index WHERE game_id = ? AND era_number = 1",
                Integer.class,
                gameId);
    }

    private long count(UUID gameId, String eventType) {
        return messagesFor(gameId).stream()
                .filter(m -> eventType.equals(m.eventType()))
                .count();
    }

    private List<UUID> paradoxIds(UUID gameId) {
        return messagesFor(gameId).stream()
                .filter(m -> PARADOX_DETECTED.equals(m.eventType()))
                .flatMap(m -> ((List<?>) m.payload().get("paradoxes")).stream())
                .map(p -> UUID.fromString((String) ((Map<?, ?>) p).get("paradoxId")))
                .toList();
    }

    private UUID winnerOf(UUID gameId, UUID eventId) {
        return messagesFor(gameId).stream()
                .filter(m -> OUTCOME_APPLIED.equals(m.eventType()))
                .filter(m -> eventId.toString().equals(m.payload().get("eventId")))
                .map(m -> UUID.fromString((String) m.payload().get("winningOutcomeId")))
                .findFirst()
                .orElseThrow();
    }

    private List<TimelineEventsTestCollector.CollectedMessage> messagesFor(UUID gameId) {
        return collector.received.stream()
                .filter(m -> gameId.toString().equals(m.payload().get("gameId")))
                .toList();
    }

    private static RequestPostProcessor controlToken() {
        return jwt().jwt(token -> token.subject("operator").claim("scope", "simulation:control"))
                .authorities(new SimpleGrantedAuthority("SCOPE_simulation:control"));
    }

    private static String contextBody() {
        return """
                {"schemaVersion":1,"caseKey":"%s","seed":"%s","entropyVersion":"SHA256_V1","manifestDigest":"%s",
                 "logicalTime":"2026-03-01T10:00:00Z",
                 "seats":[{"seatIndex":0,"playerId":"%s","faction":"ERASERS"},
                          {"seatIndex":1,"playerId":"%s","faction":"PROPHETS"},
                          {"seatIndex":2,"playerId":"%s","faction":"WEAVERS"}]}
                """.formatted(CASE_KEY, SEED, DIGEST, PLAYER_0, PLAYER_1, PLAYER_2);
    }

    private static String clockBody(UUID operationId, long expectedRevision, String targetTime) {
        return """
                {"operationId":"%s","expectedRevision":%d,"targetTime":"%s"}
                """.formatted(operationId, expectedRevision, targetTime);
    }
}
