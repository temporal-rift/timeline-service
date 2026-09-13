package io.github.temporalrift.timeline.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import io.github.temporalrift.timeline.shared.PlayerPrincipal;

@ExtendWith(MockitoExtension.class)
class PlayerAuthenticationConverterTest {

    @Mock
    private Jwt jwt;

    @InjectMocks
    private PlayerAuthenticationConverter converter;

    @Test
    @DisplayName("Given a JWT with a missing sub claim, when converting, then InvalidBearerTokenException is thrown")
    void givenMissingSubClaim_whenConverting_thenThrowsInvalidBearerTokenException() {
        // given
        given(jwt.getSubject()).willReturn(null);

        // when / then
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("Given an opaque OIDC subject, when converting, then derives a stable player ID")
    void givenOpaqueOidcSubject_whenConverting_thenDerivesStablePlayerId() throws Exception {
        // given
        var issuer = "https://kronen.eu.auth0.com/";
        var subject = "auth0|test-user-123";
        var expectedPlayerId = UUID.nameUUIDFromBytes((issuer + "\0" + subject).getBytes(StandardCharsets.UTF_8));
        given(jwt.getSubject()).willReturn(subject);
        given(jwt.getIssuer()).willReturn(URI.create(issuer).toURL());

        // when
        var result = converter.convert(jwt);

        // then
        assertThat(((PlayerPrincipal) result.getPrincipal()).playerId()).isEqualTo(expectedPlayerId);
    }

    @Test
    @DisplayName(
            "Given an opaque subject without an issuer, when converting, then InvalidBearerTokenException is thrown")
    void givenOpaqueSubjectWithoutIssuer_whenConverting_thenThrowsInvalidBearerTokenException() {
        // given
        given(jwt.getSubject()).willReturn("auth0|test-user-123");
        given(jwt.getIssuer()).willReturn(null);

        // when / then
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("Given a blank sub claim, when converting, then InvalidBearerTokenException is thrown")
    void givenBlankSubClaim_whenConverting_thenThrowsInvalidBearerTokenException() {
        // given
        given(jwt.getSubject()).willReturn("   ");

        // when / then
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName(
            "Given valid UUID sub claim, when converting, then returns PlayerAuthenticationToken with correct playerId")
    void givenValidUuidSubClaim_whenConverting_thenReturnsTokenWithCorrectPlayerId() {
        // given
        var playerId = UUID.randomUUID();
        given(jwt.getSubject()).willReturn(playerId.toString());

        // when
        var result = converter.convert(jwt);

        // then
        assertThat(result).isInstanceOf(PlayerAuthenticationToken.class);
        assertThat(result.getPrincipal()).isInstanceOf(PlayerPrincipal.class);
        assertThat(((PlayerPrincipal) result.getPrincipal()).playerId()).isEqualTo(playerId);
    }
}
