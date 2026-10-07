package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.timeline.TestSecurityConfig;
import io.github.temporalrift.timeline.infrastructure.config.SecurityConfig;
import io.github.temporalrift.timeline.infrastructure.config.SimulationControlSecurityConfig;

@WebMvcTest(controllers = OrdinaryDeploymentSimulationControlTest.Probe.class)
@Import({
    SecurityConfig.class,
    SimulationControlSecurityConfig.class,
    TestSecurityConfig.class,
    OrdinaryDeploymentSimulationControlTest.Probe.class
})
class OrdinaryDeploymentSimulationControlTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    private static RequestPostProcessor controlToken() {
        return jwt().jwt(token ->
                        token.subject("00000000-0000-4000-8000-000000000010").claim("scope", "simulation:control"))
                .authorities(new SimpleGrantedAuthority("SCOPE_simulation:control"));
    }

    @Test
    @DisplayName("an ordinary deployment registers no simulation control beans at all")
    void ordinaryDeployment_hasNoControlBeans() {
        assertThat(context.getBeanNamesForType(SimulationControlController.class))
                .isEmpty();
    }

    @Test
    @DisplayName("an ordinary deployment answers 404 on every control route, even for a control-scoped token")
    void control_inOrdinaryDeployment_returns404() throws Exception {
        expectUnavailable(mockMvc.perform(put("/internal/simulation/v1/execution")
                .with(controlToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")));
        expectUnavailable(
                mockMvc.perform(get("/internal/simulation/v1/checkpoint").with(controlToken())));
        expectUnavailable(mockMvc.perform(put("/internal/simulation/v1/clock")
                .with(controlToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")));
    }

    @Test
    @DisplayName("an ordinary deployment answers 404 to an unauthenticated caller instead of asking for a token")
    void control_withoutToken_inOrdinaryDeployment_returns404() throws Exception {
        expectUnavailable(mockMvc.perform(get("/internal/simulation/v1/checkpoint")));
    }

    private static void expectUnavailable(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SIMULATION_CONTROL_UNAVAILABLE"));
    }

    @RestController
    static class Probe {

        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
