package com.agentforge.app.security;

import com.agentforge.core.AgentForgeConstants;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    private final SecurityProperties properties;

    public SecurityConfiguration(SecurityProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
        SecurityProperties.Jwt jwt = properties.getJwt();
        return http
                // Bearer tokens only: there is no cookie or server-side session to forge
                // a request against, so CSRF protection has nothing to protect here.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(org.springframework.security.web.server.savedrequest.NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(
                        exchanges ->
                                exchanges
                                        // Liveness and readiness only. The verbose
                                        // /api/v1/system/health endpoint stays authenticated
                                        // because it reports configuration topology.
                                        .pathMatchers("/actuator/health/**", "/actuator/info")
                                        .permitAll()
                                        .pathMatchers(AgentForgeConstants.API_PREFIX + "/**")
                                        .authenticated()
                                        // Anything not explicitly listed is denied, so a new
                                        // controller cannot become public by being forgotten.
                                        .anyExchange()
                                        .denyAll())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.jwt(
                                        jwtSpec ->
                                                jwtSpec.jwtDecoder(jwtDecoder)
                                                        .jwtAuthenticationConverter(
                                                                new JwtAuthenticationConverter(
                                                                        jwt.getUserIdClaim(),
                                                                        jwt.getRolesClaim()))))
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(
                                                new HttpStatusServerEntryPoint(
                                                        HttpStatus.UNAUTHORIZED))
                                        .accessDeniedHandler(
                                                new HttpStatusServerAccessDeniedHandler(
                                                        HttpStatus.FORBIDDEN)))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        SecurityProperties.Jwt jwt = properties.getJwt();
        if (jwt.usesIssuer()) {
            NimbusReactiveJwtDecoder decoder =
                    (NimbusReactiveJwtDecoder)
                            ReactiveJwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
            decoder.setJwtValidator(
                    validators(JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri())));
            return decoder;
        }
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder.withSecretKey(
                                new SecretKeySpec(
                                        jwt.getSharedSecret().getBytes(StandardCharsets.UTF_8),
                                        "HmacSHA256"))
                        .macAlgorithm(MacAlgorithm.HS256)
                        .build();
        decoder.setJwtValidator(validators(JwtValidators.createDefault()));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> validators(OAuth2TokenValidator<Jwt> base) {
        String audience = properties.getJwt().getAudience();
        if (audience.isEmpty()) {
            return base;
        }
        return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                base,
                new JwtClaimValidator<List<String>>(
                        JwtClaimNames.AUD,
                        claim -> claim != null && claim.contains(audience)));
    }
}
