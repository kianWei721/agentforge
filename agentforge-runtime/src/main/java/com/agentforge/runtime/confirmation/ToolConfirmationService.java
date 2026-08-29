package com.agentforge.runtime.confirmation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

public class ToolConfirmationService {

    private static final int MAX_SUMMARY_LENGTH = 512;

    private final ToolConfirmationRepository repository;
    private final Duration ttl;
    private final Clock clock;

    public ToolConfirmationService(
            ToolConfirmationRepository repository,
            Duration ttl,
            Clock clock) {
        this.repository = repository;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("confirmation ttl must be positive");
        }
        this.ttl = ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ToolConfirmationService(ToolConfirmationRepository repository, Duration ttl) {
        this(repository, ttl, Clock.systemUTC());
    }

    public ToolConfirmationResolution resolve(
            String requestedExecutionId,
            String requestedToolCallId,
            ToolConfirmationBinding binding,
            String summary) {
        String executionId = required(requestedExecutionId, "requestedExecutionId");
        String toolCallId = required(requestedToolCallId, "requestedToolCallId");
        String activeKey = bindingKey(binding);
        Instant now = clock.instant();

        Optional<ToolConfirmation> consumed =
                repository.consumeApproved(activeKey, executionId, toolCallId, now);
        if (consumed.isPresent()) {
            return ToolConfirmationResolution.authorized(consumed.get());
        }

        ToolConfirmation active =
                repository.createOrGetActive(
                        new ToolConfirmation(
                                UUID.randomUUID().toString(),
                                activeKey,
                                executionId,
                                toolCallId,
                                null,
                                null,
                                binding.agentId(),
                                binding.userId(),
                                binding.sessionId(),
                                binding.toolName(),
                                binding.argumentFingerprint(),
                                normalizeSummary(summary, binding.toolName()),
                                ToolConfirmationStatus.PENDING,
                                now.plus(ttl),
                                null,
                                null,
                                null,
                                now,
                                now),
                        now);

        if (active.status() == ToolConfirmationStatus.APPROVED) {
            Optional<ToolConfirmation> racedApproval =
                    repository.consumeApproved(activeKey, executionId, toolCallId, clock.instant());
            if (racedApproval.isPresent()) {
                return ToolConfirmationResolution.authorized(racedApproval.get());
            }
        }
        return ToolConfirmationResolution.required(active);
    }

    public ToolConfirmation get(String confirmationId) {
        return repository
                .findById(required(confirmationId, "confirmationId"))
                .orElseThrow(() -> new ToolConfirmationNotFoundException(confirmationId));
    }

    public ToolConfirmation approve(String confirmationId, String userId) {
        return repository.approve(
                required(confirmationId, "confirmationId"),
                required(userId, "userId"),
                clock.instant());
    }

    public ToolConfirmation reject(String confirmationId, String userId) {
        return repository.reject(
                required(confirmationId, "confirmationId"),
                required(userId, "userId"),
                clock.instant());
    }

    public Duration ttl() {
        return ttl;
    }

    static String bindingKey(ToolConfirmationBinding binding) {
        String material =
                component(binding.agentId())
                        + component(binding.userId())
                        + component(binding.sessionId())
                        + component(binding.toolName())
                        + component(binding.argumentFingerprint());
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value + "|";
    }

    private static String normalizeSummary(String summary, String fallback) {
        String value = summary == null || summary.isBlank() ? fallback : summary.trim();
        return value.length() <= MAX_SUMMARY_LENGTH
                ? value
                : value.substring(0, MAX_SUMMARY_LENGTH);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
