package com.agentforge.core.model;

/**
 * A model call through the model gateway failed. Carries the gateway's own error code and request
 * id, so callers can decide on retries by meaning rather than by HTTP status (429 covers both a
 * temporary rate limit and an exhausted budget; 504 covers both a missed deadline and an upstream
 * that may already have charged us).
 */
public class ModelCallFailedException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;
    private final String gatewayRequestId;

    public ModelCallFailedException(
            String errorCode, boolean retryable, String gatewayRequestId, String message) {
        this(errorCode, retryable, gatewayRequestId, message, null);
    }

    public ModelCallFailedException(
            String errorCode, boolean retryable, String gatewayRequestId, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.gatewayRequestId = gatewayRequestId;
    }

    /**
     * Gateway error codes after which an automatic retry is safe. Never retry an unknown outcome
     * (the vendor may already have charged) or a spent budget (it will not have refilled).
     */
    public static boolean retryable(String gatewayErrorCode) {
        return switch (gatewayErrorCode == null ? "" : gatewayErrorCode) {
            case "RATE_LIMITED", "NO_UPSTREAM_AVAILABLE", "DEADLINE_EXCEEDED", "GATEWAY_UNREACHABLE" -> true;
            default -> false;
        };
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String gatewayRequestId() {
        return gatewayRequestId;
    }

    /** The first ModelCallFailedException in a cause chain, if any. */
    public static ModelCallFailedException find(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelCallFailedException failure) {
                return failure;
            }
        }
        return null;
    }
}
