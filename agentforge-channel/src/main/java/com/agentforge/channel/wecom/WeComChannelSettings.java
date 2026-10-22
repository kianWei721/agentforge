package com.agentforge.channel.wecom;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Who may talk to the bot and as whom.
 *
 * @param directUsers WeCom userid to AgentForge user id, for single chats
 * @param groupUsers group chat id to the AgentForge user id the whole group acts as. The answer is
 *     read by everyone in the group, so retrieval must run as the group, never as whoever asked.
 *     These ids must start with {@link #GROUP_USER_PREFIX}; the HTTP API refuses that prefix, so no
 *     person can sign in as a group and read or write its session.
 * @param allowedTools the channel agent may use only these tools; an agent configured with any
 *     other tool is refused
 * @param allowStaffGroups off by default: WeCom does not tell us when members join or leave, so a
 *     staff group that gains an outside member would leak internal documents
 */
public record WeComChannelSettings(
        String botId,
        String agentId,
        Set<String> allowedTools,
        Map<String, String> directUsers,
        Map<String, String> groupUsers,
        boolean allowStaffGroups,
        Duration turnTimeout) {

    public static final String GROUP_USER_PREFIX = "wecom-group:";

    public WeComChannelSettings {
        requireText(botId, "botId");
        requireText(agentId, "agentId");
        allowedTools = Set.copyOf(allowedTools);
        directUsers = Map.copyOf(directUsers);
        groupUsers = Map.copyOf(groupUsers);
        directUsers.forEach(
                (wecomUserId, userId) -> {
                    if (userId.startsWith(GROUP_USER_PREFIX)) {
                        throw new IllegalArgumentException(
                                "WeCom user '" + wecomUserId + "' maps to a group-only user id");
                    }
                });
        groupUsers.forEach(
                (chatId, userId) -> {
                    if (!userId.startsWith(GROUP_USER_PREFIX)) {
                        throw new IllegalArgumentException(
                                "WeCom group '"
                                        + chatId
                                        + "' must act as a '"
                                        + GROUP_USER_PREFIX
                                        + "' user, got '"
                                        + userId
                                        + "'");
                    }
                });
        if (turnTimeout == null || turnTimeout.isZero() || turnTimeout.isNegative()) {
            throw new IllegalArgumentException("turnTimeout must be positive");
        }
    }

    public static boolean isGroupUser(String userId) {
        return userId != null && userId.trim().startsWith(GROUP_USER_PREFIX);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
