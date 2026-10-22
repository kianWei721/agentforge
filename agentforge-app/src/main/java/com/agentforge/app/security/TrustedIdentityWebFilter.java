package com.agentforge.app.security;

import com.agentforge.channel.wecom.WeComChannelSettings;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.core.security.PrincipalDirectory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The only place a request gets an identity.
 *
 * <p>Deployment contract: AgentForge sits behind an authenticating gateway that verifies the
 * caller's token, strips any client-supplied {@value #USER_ID_HEADER}, and sets it from the token.
 * Controllers never read a user id from bodies or query parameters; they receive the resolved
 * {@link AccessPrincipal} through {@link AccessPrincipalArgumentResolver}.
 */
@Component
public class TrustedIdentityWebFilter implements WebFilter {

    public static final String USER_ID_HEADER = "X-AgentForge-User-Id";
    static final String PRINCIPAL_ATTRIBUTE = AccessPrincipal.class.getName();

    private static final String API_PATH_PREFIX = AgentForgeConstants.API_PREFIX + "/";
    private static final String HEALTH_PATH = AgentForgeConstants.API_PREFIX + "/system/health";

    private final PrincipalDirectory principals;

    public TrustedIdentityWebFilter(PrincipalDirectory principals) {
        this.principals = principals;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!path.startsWith(API_PATH_PREFIX) || path.equals(HEALTH_PATH)) {
            return chain.filter(exchange);
        }
        String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
        // A WeCom group acts as a synthetic user; no person may sign in as it and read or write
        // the group's session.
        Optional<AccessPrincipal> principal =
                WeComChannelSettings.isGroupUser(userId)
                        ? Optional.empty()
                        : principals.resolve(userId);
        if (principal.isEmpty()) {
            return unauthorized(exchange.getResponse());
        }
        exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal.get());
        return chain.filter(exchange);
    }

    private static Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Same shape as ApiError. The message is constant, so nothing from the request is echoed.
        String body =
                "{\"code\":\"UNAUTHENTICATED\",\"message\":\""
                        + USER_ID_HEADER
                        + " is missing or not a registered principal\",\"timestamp\":\""
                        + Instant.now()
                        + "\"}";
        return response.writeWith(
                Mono.just(
                        response.bufferFactory()
                                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
