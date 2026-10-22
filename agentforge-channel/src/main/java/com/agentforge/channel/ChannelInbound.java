package com.agentforge.channel;

import java.time.Instant;

/**
 * One inbound channel message. The unique (channel, bot, message id) row is what makes a
 * redelivered message run at most once, and it links the message to the execution it started.
 *
 * @param chatId the group chat id; null for single chats
 * @param agentId null when the message was rejected before routing
 */
public record ChannelInbound(
        String id,
        String channel,
        String botId,
        String msgId,
        String chatType,
        String chatId,
        String senderUserId,
        String agentId,
        String userId,
        String sessionKey,
        String executionId,
        ChannelInboundStatus status,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {}
