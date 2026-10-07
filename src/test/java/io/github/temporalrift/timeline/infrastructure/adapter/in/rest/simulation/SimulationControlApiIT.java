package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.temporalrift.timeline.TestSecurityConfig;
import io.github.temporalrift.timeline.application.port.in.AdvanceSimulationClockUseCase;
import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.application.port.in.GetSimulationCheckpointUseCase;
import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ClockRegressionException;
import io.github.temporalrift.timeline.domain.execution.EntropyVersion;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.execution.ExecutionState;
import io.github.temporalrift.timeline.domain.execution.IdempotencyConflictException;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SimulationSeat;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.execution.StaleExecutionRevisionException;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.infrastructure.config.SecurityConfig;
import io.github.temporalrift.timeline.infrastructure.config.SimulationControlSecurityConfig;

@WebMvcTest(controllers = SimulationControlController.class, properties = "game.simulation.enabled=true")
@Import({
    SecurityConfig.class,
    SimulationControlSecurityConfig.class,
    TestSecurityConfig.class,
    SimulationControlMapperImpl.class
})
class SimulationControlApiIT {

    private static final String EXECUTION_PATH = "/internal/simulation/v1/execution";
    private static final String CHECKPOINT_PATH = "/internal/simulation/v1/checkpoint";
    private static final String CLOCK_PATH = "/internal/simulation/v1/clock";

    private static final UUID CASE_KEY = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    private static final UUID PLAYER_0 = UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID PLAYER_1 = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID PLAYER_2 = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final UUID OPERATION = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001");
    private static final String DIGEST = "a".repeat(64);
    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ConfigureSimulationExecutionUseCase configureExecution;

    @MockitoBean
    GetSimulationCheckpointUseCase getCheckpoint;

    @MockitoBean
    AdvanceSimulationClockUseCase advanceClock;

    private static RequestPostProcessor controlToken() {
        return jwt().jwt(token -> token.subject("operator").claim("scope", "simulation:control"))
                .authorities(new SimpleGrantedAuthority("SCOPE_simulation:control"));
    }

    private static RequestPostProcessor participantToken() {
        return jwt().jwt(token -> token.subject(PLAYER_0.toString()).claim("scope", "play"))
                .authorities(new SimpleGrantedAuthority("SCOPE_play"));
    }

    private static String contextBody(String seed, String secondFaction) {
        return """
                {"schemaVersion":1,"caseKey":"%s","seed":"%s","entropyVersion":"SHA256_V1","manifestDigest":"%s",
                 "logicalTime":"2026-03-01T10:00:00Z",
                 "seats":[{"seatIndex":0,"playerId":"%s","faction":"ERASERS"},
                          {"seatIndex":1,"playerId":"%s","faction":"%s"},
                          {"seatIndex":2,"playerId":"%s","faction":"WEAVERS"}]}
                """.formatted(CASE_KEY, seed, DIGEST, PLAYER_0, PLAYER_1, secondFaction, PLAYER_2);
    }

    private static String clockBody(long expectedRevision, String targetTime) {
        return """
                {"operationId":"%s","expectedRevision":%d,"targetTime":"%s"}
                """.formatted(OPERATION, expectedRevision, targetTime);
    }

    private static ExecutionCheckpoint checkpoint() {
        return new ExecutionCheckpoint(
                CASE_KEY,
                DIGEST,
                0,
                START,
                null,
                ExecutionState.READY,
                true,
                0,
                0,
                0,
                null,
                List.of(new SourceWatermark("timeline-service.futureevent.game-events", "game.events", 0, 7)));
    }

    @Test
    @DisplayName("a request without a bearer token is rejected as unauthenticated")
    void control_withoutToken_returns401() throws Exception {
        mockMvc.perform(put(EXECUTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("42", "PROPHETS")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(configureExecution);
    }

    @Test
    @DisplayName("participant credentials are forbidden on every control route and learn nothing")
    void control_withParticipantToken_returns403WithoutDisclosure() throws Exception {
        mockMvc.perform(put(EXECUTION_PATH)
                        .with(participantToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("42", "PROPHETS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIMULATION_CONTROL_FORBIDDEN"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("42"))));
        mockMvc.perform(get(CHECKPOINT_PATH).with(participantToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIMULATION_CONTROL_FORBIDDEN"));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(participantToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(0, "2026-03-01T10:01:00Z")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIMULATION_CONTROL_FORBIDDEN"));

        verifyNoInteractions(configureExecution, getCheckpoint, advanceClock);
    }

    @Test
    @DisplayName("a control token configures the execution and receives the revision-zero checkpoint")
    void configure_withControlToken_returnsCheckpointAndPassesTheContext() throws Exception {
        given(configureExecution.handle(any())).willReturn(checkpoint());

        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("18446744073709551615", "PROPHETS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseKey").value(CASE_KEY.toString()))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.drained").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"gameId\":null")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"nextDeadline\":null")))
                .andExpect(jsonPath("$.sourceWatermarks[0].nextOffset").value(7));

        var captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(configureExecution).handle(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new ExecutionContext(
                        CASE_KEY,
                        new Seed("18446744073709551615"),
                        EntropyVersion.SHA256_V1,
                        DIGEST,
                        START,
                        List.of(
                                new SimulationSeat(0, PLAYER_0, MemberFaction.ERASERS),
                                new SimulationSeat(1, PLAYER_1, MemberFaction.PROPHETS),
                                new SimulationSeat(2, PLAYER_2, MemberFaction.WEAVERS))));
    }

    @Test
    @DisplayName("a malformed or rule-violating context is a 400 with the published code")
    void configure_invalidContext_returns400() throws Exception {
        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("18446744073709551616", "PROPHETS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_CONTEXT"));
        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("42", "ERASERS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_CONTEXT"));
        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_CONTEXT"));

        verifyNoInteractions(configureExecution);
    }

    @Test
    @DisplayName("a different context or an active lane is a 409 conflict")
    void configure_conflictingContext_returns409() throws Exception {
        given(configureExecution.handle(any())).willThrow(new ExecutionContextConflictException("different"));

        mockMvc.perform(put(EXECUTION_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contextBody("42", "PROPHETS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_CONTEXT_CONFLICT"));
    }

    @Test
    @DisplayName("the checkpoint is a conflict until an execution is configured")
    void checkpoint_beforeConfiguration_returns409() throws Exception {
        given(getCheckpoint.handle()).willThrow(new ExecutionNotConfiguredException());

        mockMvc.perform(get(CHECKPOINT_PATH).with(controlToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("a clock advance returns its acknowledgement")
    void advance_withControlToken_returnsAcknowledgement() throws Exception {
        var target = Instant.parse("2026-03-01T10:01:01Z");
        given(advanceClock.handle(any())).willReturn(new ClockAcknowledgement(OPERATION, 1, target));

        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(0, "2026-03-01T10:01:01Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(OPERATION.toString()))
                .andExpect(jsonPath("$.appliedRevision").value(1));

        var captor = ArgumentCaptor.forClass(ClockAdvance.class);
        verify(advanceClock).handle(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ClockAdvance(OPERATION, 0, target));
    }

    @Test
    @DisplayName("idempotency conflict and stale revision are 409, clock regression is 422")
    void advance_rejectedOperations_useThePublishedErrors() throws Exception {
        given(advanceClock.handle(any()))
                .willThrow(new IdempotencyConflictException(OPERATION))
                .willThrow(new StaleExecutionRevisionException(0, 2))
                .willThrow(new ClockRegressionException(START, START.plusSeconds(5)));

        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(0, "2026-03-01T10:01:01Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(0, "2026-03-01T10:01:01Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_EXECUTION_REVISION"));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(0, "2026-03-01T10:01:01Z")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLOCK_REGRESSION"));
    }

    @Test
    @DisplayName("a malformed clock advance is a 400 with the published code")
    void advance_malformedBody_returns400() throws Exception {
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockBody(-1, "2026-03-01T10:01:01Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLOCK_ADVANCE"));
        mockMvc.perform(put(CLOCK_PATH)
                        .with(controlToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLOCK_ADVANCE"));

        verifyNoInteractions(advanceClock);
    }
}
