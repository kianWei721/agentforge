package com.agentforge.runtime.confirmation;

public class ToolConfirmationNotFoundException extends RuntimeException {

    public ToolConfirmationNotFoundException(String confirmationId) {
        super("Tool confirmation not found: " + confirmationId);
    }
}
