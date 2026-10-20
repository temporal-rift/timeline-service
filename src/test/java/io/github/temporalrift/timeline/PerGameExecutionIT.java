package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import io.github.temporalrift.timeline.application.port.in.CloseSimulationExecutionUseCase;
import io.github.temporalrift.timeline.application.port.in.GetSimulationCheckpointUseCase;
import io.github.temporalrift.timeline.domain.execution.EffectiveRules;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

/**
 * One running instance opens, plays and closes several execution contexts, each with its own rules, and never lets
 * work of a closed context act under the next one.
 */
@SpringBootTest(properties = "game.simulation.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class, GameEventsTestPublisher.class})
class PerGameExecutionIT {

    private static final String EXECUTION_PATH = "/internal/simulation/v1/execution";
    private static final String CHECKPOINT_PATH = "/internal/simulation/v1/checkpoint";
    private static final String CLOCK_PATH = "/internal/simulation/v1/clock";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";
    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GameEventsTestPublisher gameEvents;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    GetSimulationCheckpointUseCase getCheckpoint;

    @Autowired
    CloseSimulationExecutionUseCase closeExecution;

    @Autowired
    ExecutionRepository executions;

    @BeforeEach
    void closeAnyOpenContext() {
        executions
                .find()
                .ifPresent(
                        execution -> closeExecution.handle(execution.context().caseKey()));
    }

    @Test
    @DisplayName("a mismatched rules digest is a 400")
    void configure_mismatchedRulesDigest_isRejected() throws Exception {
        var context = context(UUID.randomUUID(), rules(Map.of()), START);
        var body = contextBody(context).replace(context.rulesDigest(), "b".repeat(64));

        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_CONTEXT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("rulesDigest")));
    }

    @Test
    @DisplayName("a used rule outside its range is a 400 whose detail names the key")
    void configure_invalidUsedRule_namesTheKey() throws Exception {
        var properties = ExecutionContextTestData.baselineProperties();
        properties.put("game.rules.probability.suppress-shift.I", 10);
        var context = context(UUID.randomUUID(), rules(properties), START);

        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody(context)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_CONTEXT"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("game.rules.probability.suppress-shift.I")));
    }

    @Test
    @DisplayName("two contexts on one instance apply their own push-shift")
    void twoContexts_applyTheirOwnPushShift() throws Exception {
        var modifiedProperties = ExecutionContextTestData.baselineProperties();
        modifiedProperties.put("game.rules.probability.push-shift.II", 25);
        modifiedProperties.put("game.rules.probability.suppress-shift.II", -25);
        var first = context(UUID.randomUUID(), rules(modifiedProperties), START);
        var second = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);

        open(first);
        assertThat(pushedWeight()).isEqualTo(65);
        close(first.caseKey());

        open(second);
        assertThat(pushedWeight()).isEqualTo(60);
        close(second.caseKey());
    }

    @Test
    @DisplayName("the last closed case closes again harmlessly and the next context starts at its own time")
    void close_repeatsLastCaseAndStartsTheNextAtItsOwnTime() throws Exception {
        var first = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);
        var second = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START.plusSeconds(5));
        open(first);
        var advance = """
                {"operationId":"%s","expectedRevision":0,"targetTime":"2026-03-01T10:01:00Z"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(advance))
                .andExpect(status().isOk());

        close(first.caseKey());
        close(first.caseKey());
        open(second);

        mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.logicalTime").value("2026-03-01T10:00:05Z"))
                .andExpect(jsonPath("$.rulesDigest").value(second.rulesDigest()));
        close(first.caseKey());
        mockMvc.perform(delete(EXECUTION_PATH)
                        .param("caseKey", UUID.randomUUID().toString())
                        .with(controlToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_CONTEXT_CONFLICT"));
        close(second.caseKey());
    }

    @Test
    @DisplayName("a closed context's due timer never fires under the next context")
    void close_discardsThePendingTimer() throws Exception {
        var first = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);
        var second = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);
        var gameId = UUID.randomUUID();
        var futureEventId = UUID.randomUUID();
        var tieA = UUID.randomUUID();
        var tieB = UUID.randomUUID();

        open(first);
        gameEvents.eraStarted(gameId, 1, seats());
        gameEvents.threeOutcomeEventDrawn(gameId, 1, futureEventId, tieA, 45, tieB, 35, UUID.randomUUID(), 20);
        awaitIndexed(gameId);
        gameEvents.cardPlayed(gameId, 1, futureEventId, "COLLIDE", "II", tieA, tieB);
        gameEvents.actionRoundClosed(gameId, 1, 1);
        gameEvents.resolutionStarted(gameId, 1, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30)).until(() -> count(gameId, PARADOX_DETECTED) == 1);

        close(first.caseKey());
        open(second);

        var advance = """
                {"operationId":"%s","expectedRevision":0,"targetTime":"2026-03-01T10:00:02Z"}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(advance))
                .andExpect(status().isOk());

        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .until(() -> count(gameId, PARADOX_CASCADED) == 0);
        mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueTimersPending").value(0))
                .andExpect(jsonPath("$.nextDeadline").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("a late record of a closed game runs no handler but advances the watermark")
    void closedGameRecord_isIgnoredWhileTheWatermarkAdvances() throws Exception {
        var first = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);
        var second = context(UUID.randomUUID(), ExecutionContextTestData.RULES, START);
        var closedGame = UUID.randomUUID();
        var closedGameEvent = UUID.randomUUID();

        open(first);
        gameEvents.eraStarted(closedGame, 1, seats());
        await().atMost(Duration.ofSeconds(30))
                .until(() -> closedGame.equals(
                        executions.find().map(execution -> execution.gameId()).orElse(null)));
        close(first.caseKey());
        open(second);
        var watermarkBefore = sumWatermarks();

        gameEvents.publish(
                closedGame,
                "EraStarted",
                Map.of("gameId", closedGame, "eraNumber", 1, "carryOverEventIds", List.of(), "playerIds", seats()),
                closedGameEvent);

        await().atMost(Duration.ofSeconds(30)).until(() -> sumWatermarks() > watermarkBefore);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM era_players WHERE game_id = ?", Integer.class, closedGame))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, closedGameEvent))
                .isZero();
    }

    @Test
    @DisplayName("three consecutive games on one instance all reach TERMINAL with their own rules")
    void threeGames_oneInstance_reachTerminal() throws Exception {
        for (var index = 1; index <= 3; index++) {
            var properties = ExecutionContextTestData.baselineProperties();
            properties.put("game.rules.probability.push-shift.II", 15 + index);
            var rules = rules(properties);
            var context = context(UUID.randomUUID(), rules, START);
            var gameId = UUID.randomUUID();

            open(context);
            mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rulesDigest").value(rules.digest()));

            gameEvents.eraStarted(gameId, 1, seats());
            gameEvents.threeOutcomeEventDrawn(
                    gameId, 1, UUID.randomUUID(), UUID.randomUUID(), 50, UUID.randomUUID(), 30, UUID.randomUUID(), 20);
            awaitIndexed(gameId);
            gameEvents.resolutionStarted(gameId, 1, UUID.randomUUID());
            await().atMost(Duration.ofSeconds(30)).until(() -> count(gameId, ERA_RESOLUTION_COMPLETED) == 1);
            gameEvents.gameEnded(gameId, "NORMAL");
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                            .andExpect(jsonPath("$.state").value("TERMINAL")));

            close(context.caseKey());
        }
    }

    private int pushedWeight() throws Exception {
        var gameId = UUID.randomUUID();
        var futureEventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        gameEvents.eraStarted(gameId, 1, seats());
        gameEvents.threeOutcomeEventDrawn(
                gameId, 1, futureEventId, UUID.randomUUID(), 35, target, 40, UUID.randomUUID(), 25);
        awaitIndexed(gameId);
        gameEvents.cardPlayed(gameId, 1, futureEventId, "PUSH", "II", null, target);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM round_action_buffer WHERE game_id = ?", Integer.class, gameId)
                        > 0);
        gameEvents.actionRoundClosed(gameId, 1, 1);
        gameEvents.resolutionStarted(gameId, 1, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30)).until(() -> count(gameId, OUTCOME_APPLIED) == 1);
        return probabilityOf(messagesFor(gameId), target);
    }

    private void open(ExecutionContext context) throws Exception {
        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody(context)))
                .andExpect(status().isOk());
    }

    private void close(UUID caseKey) throws Exception {
        mockMvc.perform(delete(EXECUTION_PATH)
                        .param("caseKey", caseKey.toString())
                        .with(controlToken()))
                .andExpect(status().isNoContent());
    }

    private void awaitIndexed(UUID gameId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM future_event_era_index WHERE game_id = ?", Integer.class, gameId)
                        > 0);
    }

    private long sumWatermarks() {
        return getCheckpoint.handle().sourceWatermarks().stream()
                .mapToLong(SourceWatermark::nextOffset)
                .sum();
    }

    private long count(UUID gameId, String eventType) {
        return messagesFor(gameId).stream()
                .filter(message -> eventType.equals(message.eventType()))
                .count();
    }

    private List<TimelineEventsTestCollector.CollectedMessage> messagesFor(UUID gameId) {
        return collector.messagesFor(gameId);
    }

    @SuppressWarnings("unchecked")
    private static int probabilityOf(List<TimelineEventsTestCollector.CollectedMessage> messages, UUID outcomeId) {
        var applied = messages.stream()
                .filter(message -> OUTCOME_APPLIED.equals(message.eventType()))
                .findFirst()
                .orElseThrow();
        var finalProbabilities = (List<Map<String, Object>>) applied.payload().get("finalProbabilities");
        return finalProbabilities.stream()
                .filter(outcome -> outcomeId.toString().equals(outcome.get("outcomeId")))
                .findFirst()
                .map(outcome -> (Number) outcome.get("probability"))
                .orElseThrow()
                .intValue();
    }

    private static List<UUID> seats() {
        return List.of(
                ExecutionContextTestData.PLAYER_0,
                ExecutionContextTestData.PLAYER_1,
                ExecutionContextTestData.PLAYER_2);
    }

    private static EffectiveRules rules(Map<String, Object> overrides) {
        var properties = ExecutionContextTestData.baselineProperties();
        properties.putAll(overrides);
        return new EffectiveRules(properties);
    }

    private static ExecutionContext context(UUID caseKey, EffectiveRules rules, Instant time) {
        return new ExecutionContext(
                caseKey,
                new Seed("42"),
                io.github.temporalrift.timeline.domain.execution.EntropyVersion.SHA256_V1,
                "c".repeat(64),
                rules,
                rules.digest(),
                time,
                ExecutionContextTestData.threeSeats());
    }

    private static String contextBody(ExecutionContext context) {
        return """
                {"schemaVersion":1,"caseKey":"%s","seed":"%s","entropyVersion":"SHA256_V1","manifestDigest":"%s",
                 "rules":{"schemaVersion":1,"properties":%s},"rulesDigest":"%s",
                 "logicalTime":"%s",
                 "seats":[{"seatIndex":0,"playerId":"%s","faction":"ERASERS"},
                          {"seatIndex":1,"playerId":"%s","faction":"PROPHETS"},
                          {"seatIndex":2,"playerId":"%s","faction":"WEAVERS"}]}
                """.formatted(
                        context.caseKey(),
                        context.seed().decimal(),
                        context.manifestDigest(),
                        context.rules().canonicalJson(),
                        context.rulesDigest(),
                        context.logicalTime(),
                        ExecutionContextTestData.PLAYER_0,
                        ExecutionContextTestData.PLAYER_1,
                        ExecutionContextTestData.PLAYER_2);
    }

    private static RequestPostProcessor controlToken() {
        return jwt().jwt(token -> token.subject("operator").claim("scope", "simulation:control"))
                .authorities(new SimpleGrantedAuthority("SCOPE_simulation:control"));
    }
}
