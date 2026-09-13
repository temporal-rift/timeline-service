package io.github.temporalrift.timeline.infrastructure.config;

import java.util.Collections;
import java.util.Objects;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import io.github.temporalrift.timeline.shared.PlayerPrincipal;

public class PlayerAuthenticationToken extends AbstractAuthenticationToken {

    private final PlayerPrincipal principal;

    public PlayerAuthenticationToken(PlayerPrincipal principal) {
        super(Collections.emptyList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public PlayerPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PlayerAuthenticationToken other)) {
            return false;
        }
        return super.equals(obj) && Objects.equals(principal, other.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), principal);
    }
}
