package com.agentforge.channel.handoff;

import com.agentforge.channel.ChannelIntent;
import java.time.Instant;

/**
 * A message handed to a person: opens the session's ticket, or is merged into the one already
 * open.
 *
 * @param userId the principal the message ran as, {@code wecom-group:<name>} for a group
 * @param customerId that principal's customer; null for staff
 * @param excerpt masked and cut; null when excerpts are off
 * @param notifyStaff whether a staff-group notice is to be sent for a new ticket
 */
public record HandoffOpening(
        String channel,
        String botId,
        String chatType,
        String chatId,
        String sessionKey,
        String userId,
        String customerId,
        String senderUserId,
        String inboundId,
        ChannelIntent intent,
        String excerpt,
        boolean notifyStaff,
        Instant at) {}
