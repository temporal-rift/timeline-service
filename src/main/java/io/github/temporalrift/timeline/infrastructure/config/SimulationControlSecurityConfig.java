package io.github.temporalrift.timeline.infrastructure.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.shared.ProblemDetails;

/**
 * Owns the control routes independently of participant authentication. An isolated simulation deployment guards
 * them by the {@code simulation:control} scope; an ordinary deployment answers that they do not exist, to every
 * caller, without reading credentials.
 */
@Configuration
public class SimulationControlSecurityConfig {

    private static final String CONTROL_ROUTES = "/internal/simulation/**";
    private static final String CONTROL_AUTHORITY = "SCOPE_simulation:control";

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
    SecurityFilterChain simulationControlFilterChain(HttpSecurity http, ObjectMapper objectMapper) {
        return build(http.securityMatcher(CONTROL_ROUTES)
                .csrf(AbstractHttpConfigurer::disable) // NOSONAR S4502 stateless bearer-token API
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(CONTROL_AUTHORITY))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.authenticationEntryPoint((request, response, exception) -> writeProblem(
                                        objectMapper,
                                        response,
                                        HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED",
                                        "A valid bearer token is required"))
                                .jwt(Customizer.withDefaults()))
                .exceptionHandling(
                        exceptions -> exceptions.accessDeniedHandler((request, response, exception) -> writeProblem(
                                objectMapper,
                                response,
                                HttpStatus.FORBIDDEN,
                                "SIMULATION_CONTROL_FORBIDDEN",
                                "The simulation:control scope is required"))));
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain simulationControlUnavailableFilterChain(HttpSecurity http, ObjectMapper objectMapper) {
        return build(http.securityMatcher(CONTROL_ROUTES)
                .csrf(AbstractHttpConfigurer::disable) // NOSONAR S4502 no route is served behind this chain
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, exception) -> writeUnavailable(objectMapper, response))
                        .accessDeniedHandler(
                                (request, response, exception) -> writeUnavailable(objectMapper, response))));
    }

    private static void writeUnavailable(ObjectMapper objectMapper, HttpServletResponse response) throws IOException {
        writeProblem(
                objectMapper,
                response,
                HttpStatus.NOT_FOUND,
                "SIMULATION_CONTROL_UNAVAILABLE",
                "Simulation control is not available in this deployment");
    }

    private static SecurityFilterChain build(HttpSecurity http) {
        try {
            return http.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the simulation control security filter chain", e);
        }
    }

    private static void writeProblem(
            ObjectMapper objectMapper, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(ProblemDetails.of(status, detail, code)));
    }
}
