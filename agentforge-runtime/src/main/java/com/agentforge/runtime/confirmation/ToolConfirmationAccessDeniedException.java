package com.agentforge.runtime.confirmation;

public class ToolConfirmationAccessDeniedException extends RuntimeException {

    public ToolConfirmationAccessDeniedException() {
        super("Tool confirmation does not belong to the requesting user");
    }
}
