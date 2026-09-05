package com.agentforge.app.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentforge.app.api.ConversationController;
import com.agentforge.app.api.ExecutionController;
import com.agentforge.app.api.ToolConfirmationController;
import com.agentforge.conversation.ConversationService;
import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionNotFoundException;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.observability.execution.ExecutionStatus;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Contract for the fix in A1: identity comes from the access token only.
 *
 * <p>Before this, {@code userId} was self-reported in the request, so any caller could
 * act as any user simply by naming them. These tests exist to keep that from coming back.
 */
@WebFluxTest(
        controllers = {
            ExecutionController.class,
            ConversationController.class,
            ToolConfirmationController.class
        })
@Import(SecurityConfiguration.class)
@TestPropertySource(
        properties = {
            "agentforge.security.jwt.shared-secret=test-secret-that-is-long-enough-for-hs256"
        })
class ApiAuthenticationContractTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Autowired private WebTestClient client;

    @MockitoBean private ExecutionService executionService;
    @MockitoBean private ConversationService conversationService;
    @MockitoBean private ToolConfirmationService confirmationService;

    @Test
    void rejectsRequestsWithoutAToken() {
        client.get()
                .uri("/api/v1/executions/exec-1")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void rejectsTokensThatAreNotSignedWithTheConfiguredKey() throws Exception {
        String forged = jwt(ALICE, "a-different-secret-that-is-also-long-enough");

        client.get()
                .uri("/api/v1/executions/exec-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void rejectsTokensWithoutAUsableSubject() throws Exception {
        String noSubject =
                signed(new JWTClaimsSet.Builder().claim("roles", List.of("USER")), SECRET);

        client.get()
                .uri("/api/v1/executions/exec-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + noSubject)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void readsTheCallerIdentityFromTheTokenNotTheQueryString() throws Exception {
        when(conversationService.listSessions(eq("agent-1"), eq(ALICE))).thenReturn(List.of());

        // The query string names bob; the token says alice. Alice must win.
        client.get()
                .uri("/api/v1/agents/agent-1/sessions?userId=" + BOB)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(ALICE, SECRET))
                .exchange()
                .expectStatus()
                .isOk();

        verify(conversationService).listSessions("agent-1", ALICE);
        verify(conversationService, never()).listSessions(any(), eq(BOB));
    }

    @Test
    void approvingAConfirmationUsesTheTokenIdentityAndIgnoresAnyBody() throws Exception {
        when(confirmationService.approve(eq("conf-1"), eq(ALICE)))
                .thenThrow(new IllegalStateException("stop after identity is resolved"));

        // A body naming bob must not change who is recorded as the approver. This is the
        // exact bypass A1 closes: approval used to trust request.userId().
        client.post()
                .uri("/api/v1/tool-confirmations/conf-1/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(ALICE, SECRET))
                .header("Content-Type", "application/json")
                .bodyValue("{\"userId\":\"" + BOB + "\"}")
                .exchange();

        verify(confirmationService).approve("conf-1", ALICE);
        verify(confirmationService, never()).approve(any(), eq(BOB));
    }

    @Test
    void oneUserCannotReadAnotherUsersExecution() throws Exception {
        when(executionService.getForUser("exec-owned-by-bob", ALICE))
                .thenThrow(new ExecutionNotFoundException("exec-owned-by-bob"));

        client.get()
                .uri("/api/v1/executions/exec-owned-by-bob")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(ALICE, SECRET))
                .exchange()
                .expectStatus()
                .isNotFound();

        verify(executionService).getForUser("exec-owned-by-bob", ALICE);
    }

    @Test
    void ownerCanReadTheirOwnExecution() throws Exception {
        when(executionService.getForUser("exec-1", ALICE)).thenReturn(execution(ALICE));

        client.get()
                .uri("/api/v1/executions/exec-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(ALICE, SECRET))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.userId")
                .isEqualTo(ALICE);
    }

    @Test
    void executionTracesAreScopedToTheOwner() throws Exception {
        when(executionService.tracesForUser("exec-1", ALICE)).thenReturn(List.of());

        client.get()
                .uri("/api/v1/executions/exec-1/traces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt(ALICE, SECRET))
                .exchange()
                .expectStatus()
                .isOk();

        verify(executionService).tracesForUser("exec-1", ALICE);
    }

    private static AgentExecution execution(String userId) {
        return new AgentExecution(
                "exec-1",
                "session-1",
                "agent-1",
                userId,
                "message-1",
                null,
                "mock",
                "mock-model",
                ExecutionStatus.RUNNING,
                Instant.now(),
                null,
                null,
                null,
                null);
    }

    private static String jwt(String subject, String secret) throws Exception {
        return signed(new JWTClaimsSet.Builder().subject(subject), secret);
    }

    private static String signed(JWTClaimsSet.Builder claims, String secret) throws Exception {
        JWTClaimsSet claimsSet =
                claims.issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000L))
                        .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
