package com.agentforge.app.api;

import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.conversation.ConversationNotFoundException;
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
