package com.agentforge.runtime.confirmation;

import java.time.Instant;
import java.util.Optional;

public interface ToolConfirmationRepository {

    ToolConfirmation createOrGetActive(ToolConfirmation draft, Instant now);

    Optional<ToolConfirmation> findById(String confirmationId);

    ToolConfirmation approve(String confirmationId, String userId, Instant now);

    ToolConfirmation reject(String confirmationId, String userId, Instant now);

    Optional<ToolConfirmation> consumeApproved(
            String activeKey,
            String consumedExecutionId,
            String consumedToolCallId,
            Instant now);
}
