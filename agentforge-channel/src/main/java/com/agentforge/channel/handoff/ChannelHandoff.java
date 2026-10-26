package com.agentforge.channel.handoff;

import java.time.Instant;

/**
 * One open request for a person, per session: messages handed off while it is open are merged
 * into it ({@code messageCount}, {@code lastInboundId}). The row is also the outbox of its
 * staff-group notice ({@code notify*}).
 */
public record ChannelHandoff(
        String id,
        String channel,
        String botId,
        String chatType,
        String chatId,
        String sessionKey,
        String userId,
        String customerId,
        String senderUserId,
        String firstInboundId,
        String lastInboundId,
        int messageCount,
        String intent,
        String intentSource,
        Double intentConfidence,
        String excerpt,
        HandoffStatus status,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        HandoffResolution resolution,
        String resolutionNote,
        NotifyStatus notifyStatus,
        int notifyAttempts,
        Instant nextNotifyAt,
        Instant notifiedAt,
        String notifyError,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt) {}
