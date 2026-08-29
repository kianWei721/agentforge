package com.agentforge.app.api;

import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.conversation.ConversationNotFoundException;
import com.agentforge.observability.execution.ExecutionNotFoundException;
import com.agentforge.runtime.confirmation.ToolConfirmationAccessDeniedException;
import com.agentforge.runtime.confirmation.ToolConfirmationInvalidStateException;
import com.agentforge.runtime.confirmation.ToolConfirmationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AgentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(AgentNotFoundException exception) {
        return ApiError.of("AGENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleConversationNotFound(ConversationNotFoundException exception) {
        return ApiError.of("CONVERSATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleExecutionNotFound(ExecutionNotFoundException exception) {
        return ApiError.of("EXECUTION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ToolConfirmationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleConfirmationNotFound(ToolConfirmationNotFoundException exception) {
        return ApiError.of("TOOL_CONFIRMATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ToolConfirmationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleConfirmationForbidden(ToolConfirmationAccessDeniedException exception) {
        return ApiError.of("TOOL_CONFIRMATION_FORBIDDEN", exception.getMessage());
    }

    @ExceptionHandler(ToolConfirmationInvalidStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConfirmationInvalidState(ToolConfirmationInvalidStateException exception) {
        return ApiError.of("TOOL_CONFIRMATION_INVALID_STATE", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(IllegalArgumentException exception) {
        return ApiError.of("BAD_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConflict(IllegalStateException exception) {
        return ApiError.of("INVALID_STATE", exception.getMessage());
    }
}
