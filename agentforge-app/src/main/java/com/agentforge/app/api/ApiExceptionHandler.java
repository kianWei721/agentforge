package com.agentforge.app.api;

import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.channel.handoff.ChannelHandoffInvalidStateException;
import com.agentforge.channel.handoff.ChannelHandoffNotFoundException;
import com.agentforge.conversation.ConversationNotFoundException;
import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.core.security.AccessDeniedException;
import com.agentforge.observability.execution.ExecutionNotFoundException;
import com.agentforge.runtime.confirmation.ToolConfirmationAccessDeniedException;
import com.agentforge.runtime.confirmation.ToolConfirmationInvalidStateException;
import com.agentforge.runtime.confirmation.ToolConfirmationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleAccessDenied(AccessDeniedException exception) {
        return ApiError.of("ACCESS_DENIED", exception.getMessage());
    }

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

    @ExceptionHandler(ChannelHandoffNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleHandoffNotFound(ChannelHandoffNotFoundException exception) {
        return ApiError.of("CHANNEL_HANDOFF_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ChannelHandoffInvalidStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleHandoffInvalidState(ChannelHandoffInvalidStateException exception) {
        return ApiError.of("CHANNEL_HANDOFF_INVALID_STATE", exception.getMessage());
    }

    /** 503 when the gateway says a retry is safe, 502 otherwise; the request id finds the ledger row. */
    @ExceptionHandler(ModelCallFailedException.class)
    public ResponseEntity<ApiError> handleModelCallFailed(ModelCallFailedException exception) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(
                        exception.retryable()
                                ? HttpStatus.SERVICE_UNAVAILABLE
                                : HttpStatus.BAD_GATEWAY);
        if (exception.gatewayRequestId() != null) {
            response.header("X-Gateway-Request-Id", exception.gatewayRequestId());
        }
        return response.body(
                ApiError.of("MODEL_" + exception.errorCode(), exception.getMessage()));
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
