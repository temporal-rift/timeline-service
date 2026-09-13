package io.github.temporalrift.timeline.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import io.github.temporalrift.timeline.shared.PlayerPrincipal;

public class PlayerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt source) {
        var sub = source.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new InvalidBearerTokenException("Missing sub claim");
        }
        try {
            return new PlayerAuthenticationToken(new PlayerPrincipal(UUID.fromString(sub)));
        } catch (IllegalArgumentException _) {
            var issuer = source.getIssuer();
            if (issuer == null) {
                throw new InvalidBearerTokenException("Missing iss claim for opaque sub claim");
            }
            var identity = issuer + "\0" + sub;
            var playerId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            return new PlayerAuthenticationToken(new PlayerPrincipal(playerId));
        }
    }
}
