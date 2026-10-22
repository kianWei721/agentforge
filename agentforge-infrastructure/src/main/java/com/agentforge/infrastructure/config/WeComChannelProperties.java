package com.agentforge.infrastructure.config;

import com.agentforge.channel.wecom.WeComChannelSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** WeCom smart bot over HTTP callbacks. Token and AES key come from the bot's admin page. */
@ConfigurationProperties(prefix = "agentforge.channel.wecom")
public class WeComChannelProperties {

    /** WeCom stops pulling a stream reply 6 minutes after the user's message. */
    static final Duration WECOM_STREAM_WINDOW = Duration.ofMinutes(6);

    private boolean enabled;
    private String token;
    private String encodingAesKey;
    private String receiveId;
    private String botId;
    private String agentId;
    private List<String> allowedTools = new ArrayList<>(List.of("search_knowledge"));
    /** Entries in {@code wecomUserId=agentforgeUserId} form. */
    private List<String> directUsers = new ArrayList<>();
    /** Entries in {@code chatId=wecom-group:...} form. */
    private List<String> groups = new ArrayList<>();
    private boolean allowStaffGroups;
    private int maxConcurrentTurns = 4;
    private int maxPendingTurns = 64;
    private int maxQueuedPerSession = 3;
    private Duration turnTimeout = Duration.ofMinutes(5);
    private Duration streamTtl = Duration.ofMinutes(10);
    private Duration timestampTolerance = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEncodingAesKey() {
        return encodingAesKey;
    }

    public void setEncodingAesKey(String encodingAesKey) {
        this.encodingAesKey = encodingAesKey;
    }

    public String getReceiveId() {
        return receiveId;
    }

    public void setReceiveId(String receiveId) {
        this.receiveId = receiveId;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools == null ? new ArrayList<>() : allowedTools;
    }

    public List<String> getDirectUsers() {
        return directUsers;
    }

    public void setDirectUsers(List<String> directUsers) {
        this.directUsers = directUsers == null ? new ArrayList<>() : directUsers;
    }

    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups == null ? new ArrayList<>() : groups;
    }

    public boolean isAllowStaffGroups() {
        return allowStaffGroups;
    }

    public void setAllowStaffGroups(boolean allowStaffGroups) {
        this.allowStaffGroups = allowStaffGroups;
    }

    public int getMaxConcurrentTurns() {
        return maxConcurrentTurns;
    }

    public void setMaxConcurrentTurns(int maxConcurrentTurns) {
        this.maxConcurrentTurns = maxConcurrentTurns;
    }

    public int getMaxPendingTurns() {
        return maxPendingTurns;
    }

    public void setMaxPendingTurns(int maxPendingTurns) {
        this.maxPendingTurns = maxPendingTurns;
    }

    public int getMaxQueuedPerSession() {
        return maxQueuedPerSession;
    }

    public void setMaxQueuedPerSession(int maxQueuedPerSession) {
        this.maxQueuedPerSession = maxQueuedPerSession;
    }

    public Duration getTurnTimeout() {
        return turnTimeout;
    }

    public void setTurnTimeout(Duration turnTimeout) {
        this.turnTimeout = turnTimeout;
    }

    public Duration getStreamTtl() {
        return streamTtl;
    }

    public void setStreamTtl(Duration streamTtl) {
        this.streamTtl = streamTtl;
    }

    public Duration getTimestampTolerance() {
        return timestampTolerance;
    }

    public void setTimestampTolerance(Duration timestampTolerance) {
        this.timestampTolerance = timestampTolerance;
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("WeCom channel requires token.");
        }
        if (encodingAesKey == null || encodingAesKey.trim().length() != 43) {
            throw new IllegalStateException("WeCom channel encoding-aes-key must be 43 characters.");
        }
        if (!StringUtils.hasText(botId) || !StringUtils.hasText(agentId)) {
            throw new IllegalStateException("WeCom channel requires bot-id and agent-id.");
        }
        if (maxConcurrentTurns < 1 || maxPendingTurns < 1 || maxQueuedPerSession < 0) {
            throw new IllegalStateException("WeCom channel turn limits must be positive.");
        }
        if (turnTimeout == null
                || turnTimeout.isNegative()
                || turnTimeout.isZero()
                || turnTimeout.compareTo(WECOM_STREAM_WINDOW) >= 0) {
            throw new IllegalStateException(
                    "WeCom channel turn-timeout must be positive and shorter than WeCom's"
                            + " 6-minute stream window.");
        }
        // A reply must stay readable for as long as WeCom may still pull it.
        if (streamTtl == null || streamTtl.compareTo(WECOM_STREAM_WINDOW) < 0) {
            throw new IllegalStateException(
                    "WeCom channel stream-ttl must cover WeCom's 6-minute stream window.");
        }
        if (timestampTolerance == null || timestampTolerance.isNegative()) {
            throw new IllegalStateException("WeCom channel timestamp-tolerance must not be negative.");
        }
        toSettings();
    }

    public WeComChannelSettings toSettings() {
        try {
            return new WeComChannelSettings(
                    botId.trim(),
                    agentId.trim(),
                    new LinkedHashSet<>(allowedTools.stream().map(String::trim).toList()),
                    pairs(directUsers, "direct-users"),
                    pairs(groups, "groups"),
                    allowStaffGroups,
                    turnTimeout);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Invalid WeCom channel configuration: " + invalid.getMessage(), invalid);
        }
    }

    private static Map<String, String> pairs(List<String> entries, String property) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "agentforge.channel.wecom." + property + " entries must be key=userId, got: '"
                                + entry + "'");
            }
            String key = entry.substring(0, separator).trim();
            if (pairs.put(key, entry.substring(separator + 1).trim()) != null) {
                throw new IllegalArgumentException(
                        "agentforge.channel.wecom." + property + " lists '" + key + "' twice");
            }
        }
        return pairs;
    }
}
