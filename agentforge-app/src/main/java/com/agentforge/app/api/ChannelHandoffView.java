package com.agentforge.app.api;

import com.agentforge.channel.handoff.ChannelHandoff;
import java.time.Instant;

/**
 * A hand-off as staff see it. The raw chat id and session key stay out: the group is shown by the
 * name operations gave it ({@code userId}).
 */
public record ChannelHandoffView(
        String id,
        String status,
        String chatType,
        String userId,
        String customerId,
        String senderUserId,
        String excerpt,
        String intentSource,
        Double intentConfidence,
        int messageCount,
        String firstInboundId,
        String lastInboundId,
        String claimedBy,
        Instant claimedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolution,
        String resolutionNote,
        String notifyStatus,
        Instant createdAt,
        Instant lastMessageAt) {

    static ChannelHandoffView from(ChannelHandoff handoff) {
        return new ChannelHandoffView(
                handoff.id(),
                handoff.status().name(),
                handoff.chatType(),
                handoff.userId(),
                handoff.customerId(),
                handoff.senderUserId(),
                handoff.excerpt(),
                handoff.intentSource(),
                handoff.intentConfidence(),
                handoff.messageCount(),
                handoff.firstInboundId(),
                handoff.lastInboundId(),
                handoff.claimedBy(),
                handoff.claimedAt(),
                handoff.resolvedBy(),
                handoff.resolvedAt(),
                handoff.resolution() == null ? null : handoff.resolution().name(),
                handoff.resolutionNote(),
                handoff.notifyStatus().name(),
                handoff.createdAt(),
                handoff.lastMessageAt());
    }
}
