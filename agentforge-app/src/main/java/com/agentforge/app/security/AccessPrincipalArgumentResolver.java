package com.agentforge.app.security;

import com.agentforge.core.security.AccessPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Injects the principal resolved by {@link TrustedIdentityWebFilter} into controller methods. */
public final class AccessPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AccessPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Mono<Object> resolveArgument(
            MethodParameter parameter,
            BindingContext bindingContext,
            ServerWebExchange exchange) {
        AccessPrincipal principal =
                exchange.getAttribute(TrustedIdentityWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "no authenticated principal"));
        }
        return Mono.just(principal);
    }
}
