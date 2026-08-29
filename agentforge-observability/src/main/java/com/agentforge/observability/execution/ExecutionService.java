package com.agentforge.observability.execution;

import java.util.List;

public class ExecutionService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 4000;

    private final ExecutionRepository repository;

    public ExecutionService(ExecutionRepository repository) {
        this.repository = repository;
    }

    public AgentExecution start(
            String sessionId,
            String agentId,
            String userId,
            String userMessageId,
            String modelProvider,
            String modelName) {
        return repository.start(
                required(sessionId, "sessionId"),
                required(agentId, "agentId"),
                required(userId, "userId"),
                required(userMessageId, "userMessageId"),
                required(modelProvider, "modelProvider"),
                required(modelName, "modelName"));
    }

    public ExecutionTraceEvent appendTrace(String executionId, TraceEventInput input) {
        if (input == null) {
            throw new IllegalArgumentException("trace input must not be null");
        }
        return repository.appendTrace(required(executionId, "executionId"), input);
    }

    public AgentExecution succeed(String executionId, String assistantMessageId) {
        return repository.markSucceeded(
                required(executionId, "executionId"), blankToNull(assistantMessageId));
    }

    public AgentExecution fail(String executionId, Throwable error) {
        String errorType = error == null ? "UnknownError" : error.getClass().getSimpleName();
        String message = error == null ? "Unknown execution failure" : error.getMessage();
        return repository.markFailed(
                required(executionId, "executionId"),
                errorType,
                truncate(message == null ? "" : message, MAX_ERROR_MESSAGE_LENGTH));
    }

    public AgentExecution cancel(String executionId) {
        return repository.markCancelled(required(executionId, "executionId"));
    }

    public AgentExecution get(String executionId) {
        String normalized = required(executionId, "executionId");
        return repository.findById(normalized)
                .orElseThrow(() -> new ExecutionNotFoundException(normalized));
    }

    public List<AgentExecution> listBySessionId(String sessionId) {
        return repository.findBySessionId(required(sessionId, "sessionId"));
    }

    public List<ExecutionTraceEvent> traces(String executionId) {
        get(executionId);
        return repository.findTraces(executionId);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
