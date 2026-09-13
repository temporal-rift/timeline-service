package io.github.temporalrift.timeline.shared;

import java.security.Principal;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the authenticated player identity for generated controller method signatures. */
public final class CurrentPlayer {

    private CurrentPlayer() {}

    public static UUID id() {
        return id(SecurityContextHolder.getContext().getAuthentication());
    }

    public static UUID id(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof PlayerPrincipal(var playerId)) {
            return playerId;
        }
        throw new AccessDeniedException("No authenticated player in security context");
    }
}
