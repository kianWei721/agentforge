package com.agentforge.app.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authentication whose principal is an {@link AuthenticatedUser}, so controllers can
 * declare {@code @AuthenticationPrincipal AuthenticatedUser} and stay independent of
 * the token format.
 */
public final class AgentForgeAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final transient Jwt token;

    public AgentForgeAuthenticationToken(
            AuthenticatedUser user, Jwt token, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.user = user;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return user.userId();
    }
}
