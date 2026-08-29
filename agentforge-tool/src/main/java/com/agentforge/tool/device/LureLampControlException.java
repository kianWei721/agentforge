package com.agentforge.tool.device;

public class LureLampControlException extends RuntimeException {

    public enum Reason {
        CONTROL_DISABLED,
        DEVICE_NOT_ALLOWED,
        DEVICE_NOT_FOUND,
        DEVICE_OFFLINE,
        PRODUCT_MISMATCH,
        AUTHENTICATION_FAILED,
        FORBIDDEN,
        BAD_REQUEST,
        BACKEND_UNAVAILABLE,
        TIMEOUT,
        INVALID_RESPONSE,
        COMMAND_REJECTED,
        STATE_UNKNOWN,
        INTERRUPTED
    }

    private final Reason reason;
    private final boolean mayHaveExecuted;

    public LureLampControlException(
            Reason reason,
            boolean mayHaveExecuted,
            String message) {
        super(message);
        this.reason = reason;
        this.mayHaveExecuted = mayHaveExecuted;
    }

    public LureLampControlException(
            Reason reason,
            boolean mayHaveExecuted,
            String message,
            Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.mayHaveExecuted = mayHaveExecuted;
    }

    public Reason reason() {
        return reason;
    }

    public boolean mayHaveExecuted() {
        return mayHaveExecuted;
    }
}
