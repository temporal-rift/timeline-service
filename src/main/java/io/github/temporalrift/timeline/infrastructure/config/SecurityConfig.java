package io.github.temporalrift.timeline.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@EnableWebSecurity
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        // Stateless bearer API: no cookie or session rides cross-site, so CSRF is inapplicable.
        return http.csrf(AbstractHttpConfigurer::disable) // NOSONAR S4502
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.authenticationEntryPoint(new UnauthorizedEntryPoint(objectMapper))
                                .jwt(jwt -> jwt.jwtAuthenticationConverter(new PlayerAuthenticationConverter())))
                .exceptionHandling(
                        exceptions -> exceptions.accessDeniedHandler(new PlayerAccessDeniedHandler(objectMapper)))
                .build();
    }
}
