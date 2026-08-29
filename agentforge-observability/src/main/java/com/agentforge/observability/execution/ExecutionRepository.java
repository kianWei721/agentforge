package com.agentforge.observability.execution;

import java.util.List;
import java.util.Optional;

public interface ExecutionRepository {

    AgentExecution start(
            String sessionId,
            String agentId,
            String userId,
            String userMessageId,
            String modelProvider,
            String modelName);

    Optional<AgentExecution> findById(String executionId);

    List<AgentExecution> findBySessionId(String sessionId);

    ExecutionTraceEvent appendTrace(String executionId, TraceEventInput input);

    List<ExecutionTraceEvent> findTraces(String executionId);

    AgentExecution markSucceeded(String executionId, String assistantMessageId);

    AgentExecution markFailed(String executionId, String errorType, String errorMessage);

    AgentExecution markCancelled(String executionId);
}
