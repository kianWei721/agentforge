package com.agentforge.tool.device;

public class DeviceDiagnosticQueryException extends RuntimeException {
    private final Reason reason;
    private final boolean retryable;

    public DeviceDiagnosticQueryException(Reason reason, boolean retryable, String message) {
        super(message);
        this.reason = reason;
        this.retryable = retryable;
    }

    public DeviceDiagnosticQueryException(
            Reason reason, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public Reason reason() { return reason; }
    public boolean retryable() { return retryable; }

    public enum Reason {
        AUTHENTICATION_FAILED, FORBIDDEN, BAD_REQUEST, BACKEND_UNAVAILABLE,
        TIMEOUT, CIRCUIT_OPEN, INVALID_RESPONSE, INTERRUPTED
    }
}
