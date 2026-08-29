package com.agentforge.runtime.confirmation;

import java.util.Objects;

public record ToolConfirmationResolution(
        boolean authorized,
        ToolConfirmation confirmation) {

    public ToolConfirmationResolution {
        Objects.requireNonNull(confirmation, "confirmation must not be null");
    }

    public static ToolConfirmationResolution authorized(ToolConfirmation confirmation) {
        return new ToolConfirmationResolution(true, confirmation);
    }

    public static ToolConfirmationResolution required(ToolConfirmation confirmation) {
        return new ToolConfirmationResolution(false, confirmation);
    }
}
