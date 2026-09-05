package com.agentforge.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.core.publisher.Mono;

/** Turns a verified JWT into an {@link AgentForgeAuthenticationToken}. */
public class JwtAuthenticationConverter
        implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final String userIdClaim;
    private final String rolesClaim;

    public JwtAuthenticationConverter(String userIdClaim, String rolesClaim) {
        this.userIdClaim = userIdClaim;
        this.rolesClaim = rolesClaim;
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        String userId = readUserId(jwt);
        List<String> roles = readRoles(jwt);
        AuthenticatedUser user;
        try {
            user = new AuthenticatedUser(userId, roles);
        } catch (IllegalArgumentException exception) {
            // A token that carries an unusable subject is rejected as a bad token (401)
            // rather than surfacing later as a 500 from a downstream length check.
            throw new InvalidBearerTokenException(exception.getMessage(), exception);
        }
        return Mono.just(new AgentForgeAuthenticationToken(user, jwt, authorities(roles)));
    }

    private String readUserId(Jwt jwt) {
        Object value = jwt.getClaim(userIdClaim);
        String userId = value == null ? "" : String.valueOf(value).trim();
        if (userId.isEmpty()) {
            throw new InvalidBearerTokenException(
                    "Token is missing a usable '" + userIdClaim + "' claim.");
        }
        return userId;
    }

    private List<String> readRoles(Jwt jwt) {
        Object value = jwt.getClaim(rolesClaim);
        if (value == null) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> addRole(roles, entry));
        } else {
            // Space-delimited is how the standard `scope` claim is encoded, so a
            // deployment can point rolesClaim at it without extra mapping.
            for (String entry : String.valueOf(value).split("\\s+")) {
                addRole(roles, entry);
            }
        }
        return List.copyOf(roles);
    }

    private static void addRole(List<String> roles, Object entry) {
        if (entry == null) {
            return;
        }
        String role = String.valueOf(entry).trim().toUpperCase(Locale.ROOT);
        if (!role.isEmpty() && !roles.contains(role)) {
            roles.add(role);
        }
    }

    private static Collection<GrantedAuthority> authorities(List<String> roles) {
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
