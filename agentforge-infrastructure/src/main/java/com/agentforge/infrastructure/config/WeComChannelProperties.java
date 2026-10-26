package com.agentforge.infrastructure.config;

import com.agentforge.channel.handoff.HandoffSettings;
import com.agentforge.channel.intent.IntentDefinition;
import com.agentforge.channel.intent.IntentRoutingSettings;
import com.agentforge.channel.intent.IntentTarget;
import com.agentforge.channel.wecom.WeComChannelSettings;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    /** A memory bound only; admission by deadline decides what is accepted. */
    private int maxPendingTurns = 1000;
    private int maxQueuedPerSession = 3;
    private Duration turnTimeout = Duration.ofMinutes(5);
    /** Assumed turn duration until real ones are measured; admission estimates waits from it. */
    private Duration expectedTurnDuration = Duration.ofSeconds(15);
    private Duration streamTtl = Duration.ofMinutes(10);
    private Duration timestampTolerance = Duration.ofMinutes(5);
    private final Intent intent = new Intent();
    private final Handoff handoff = new Handoff();

    public Intent getIntent() {
        return intent;
    }

    public Handoff getHandoff() {
        return handoff;
    }

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

    public Duration getExpectedTurnDuration() {
        return expectedTurnDuration;
    }

    public void setExpectedTurnDuration(Duration expectedTurnDuration) {
        this.expectedTurnDuration = expectedTurnDuration;
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
        // Within the reply window, which leaves a margin before WeCom stops pulling: a turn timing
        // out after it would teach admission a duration no turn can be admitted under.
        if (turnTimeout == null
                || turnTimeout.isNegative()
                || turnTimeout.isZero()
                || turnTimeout.compareTo(WeComChannelSettings.DEFAULT_REPLY_WINDOW) > 0) {
            throw new IllegalStateException(
                    "WeCom channel turn-timeout must be positive and within the reply window ("
                            + WeComChannelSettings.DEFAULT_REPLY_WINDOW
                            + ", WeCom's 6-minute stream window less a margin).");
        }
        if (expectedTurnDuration == null
                || expectedTurnDuration.isNegative()
                || expectedTurnDuration.isZero()
                || expectedTurnDuration.compareTo(turnTimeout) > 0) {
            throw new IllegalStateException(
                    "WeCom channel expected-turn-duration must be positive and within turn-timeout.");
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
        if (intent.isEnabled()) {
            intent.toSettings();
        }
        handoff.validate();
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

    /**
     * Hand-off tickets and the staff-group notice. Tickets are always opened; the notice is sent
     * only with a webhook.
     */
    public static class Handoff {
        /** The robot address holds its key: set it from the environment, never in a file. */
        static final String WEBHOOK_HOST = "qyapi.weixin.qq.com";
        /** Below this a burst could pass WeCom's 20 messages a minute per robot. */
        static final Duration MIN_NOTIFY_INTERVAL = Duration.ofSeconds(3);

        private String webhookUrl;
        private List<String> mentionUserids = new ArrayList<>();
        private String replyWhenNotified = "已转给客服同事，他们会在群里回复你。";
        private int excerptChars = 120;
        private Duration notifyInterval = Duration.ofSeconds(5);
        private int maxItemsPerMessage = 10;
        private int maxAttempts = 5;
        /** Shorter than the interval, so one slow call never runs into the next tick. */
        private Duration webhookTimeout = Duration.ofSeconds(4);
        private String zone = "Asia/Shanghai";

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public List<String> getMentionUserids() { return mentionUserids; }
        public void setMentionUserids(List<String> mentionUserids) { this.mentionUserids = mentionUserids; }
        public String getReplyWhenNotified() { return replyWhenNotified; }
        public void setReplyWhenNotified(String replyWhenNotified) { this.replyWhenNotified = replyWhenNotified; }
        public int getExcerptChars() { return excerptChars; }
        public void setExcerptChars(int excerptChars) { this.excerptChars = excerptChars; }
        public Duration getNotifyInterval() { return notifyInterval; }
        public void setNotifyInterval(Duration notifyInterval) { this.notifyInterval = notifyInterval; }
        public int getMaxItemsPerMessage() { return maxItemsPerMessage; }
        public void setMaxItemsPerMessage(int maxItemsPerMessage) { this.maxItemsPerMessage = maxItemsPerMessage; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getWebhookTimeout() { return webhookTimeout; }
        public void setWebhookTimeout(Duration webhookTimeout) { this.webhookTimeout = webhookTimeout; }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }

        public boolean notifies() {
            return StringUtils.hasText(webhookUrl);
        }

        /** Customer text goes to this address: only WeCom's own host, over https. */
        public URI webhook() {
            URI uri;
            try {
                uri = URI.create(webhookUrl.trim());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("WeCom hand-off webhook-url is not a valid address.");
            }
            if (!"https".equals(uri.getScheme()) || !WEBHOOK_HOST.equals(uri.getHost())) {
                throw new IllegalStateException(
                        "WeCom hand-off webhook-url must be https://" + WEBHOOK_HOST + "/...");
            }
            return uri;
        }

        public List<String> mentioned() {
            return mentionUserids.stream().map(String::trim).filter(StringUtils::hasText).toList();
        }

        public HandoffSettings toSettings() {
            try {
                return new HandoffSettings(notifies(), replyWhenNotified, excerptChars);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("Invalid WeCom hand-off configuration: " + invalid.getMessage(), invalid);
            }
        }

        public void validate() {
            toSettings();
            if (!notifies()) {
                return;
            }
            webhook();
            if (notifyInterval == null || notifyInterval.compareTo(MIN_NOTIFY_INTERVAL) < 0) {
                throw new IllegalStateException(
                        "WeCom hand-off notify-interval must be at least " + MIN_NOTIFY_INTERVAL + ".");
            }
            if (maxItemsPerMessage < 1 || maxItemsPerMessage > 20 || maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalStateException(
                        "WeCom hand-off max-items-per-message must be 1-20 and max-attempts 1-10.");
            }
            if (webhookTimeout == null || webhookTimeout.isNegative() || webhookTimeout.isZero()
                    || webhookTimeout.compareTo(notifyInterval) >= 0) {
                throw new IllegalStateException(
                        "WeCom hand-off webhook-timeout must be positive and shorter than notify-interval.");
            }
            try {
                ZoneId.of(zone);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("WeCom hand-off zone is not a time zone: " + zone);
            }
        }
    }

    /**
     * Intent routing: phrases decide on arrival; with the model stage, a classifier through the
     * model gateway decides the rest. Off by default.
     */
    public static class Intent {
        private boolean enabled;
        private boolean modelStage;
        private String defaultIntent = "answer";
        /** The gateway route the classifier calls; it must support structured JSON output. */
        private String modelRoute = "rag-json";
        private Duration timeout = Duration.ofSeconds(3);
        private double minConfidence = 0.7;
        private int maxInputChars = 500;
        private List<Entry> intents = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isModelStage() { return modelStage; }
        public void setModelStage(boolean modelStage) { this.modelStage = modelStage; }
        public String getDefaultIntent() { return defaultIntent; }
        public void setDefaultIntent(String defaultIntent) { this.defaultIntent = defaultIntent; }
        public String getModelRoute() { return modelRoute; }
        public void setModelRoute(String modelRoute) { this.modelRoute = modelRoute; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
        public List<Entry> getIntents() { return intents; }
        public void setIntents(List<Entry> intents) { this.intents = intents; }

        /** Fails startup on an inconsistent configuration. */
        public IntentRoutingSettings toSettings() {
            if (modelStage && !StringUtils.hasText(modelRoute)) {
                throw new IllegalStateException("agentforge.channel.wecom.intent.model-route is required.");
            }
            try {
                return new IntentRoutingSettings(
                        modelStage,
                        defaultIntent == null ? null : defaultIntent.trim(),
                        timeout,
                        minConfidence,
                        maxInputChars,
                        intents.stream().map(Entry::toDefinition).toList());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "Invalid agentforge.channel.wecom.intent: " + invalid.getMessage(), invalid);
            }
        }
    }

    /** One intent; target is answer, handoff or refuse. */
    public static class Entry {
        private String name;
        private String description;
        private List<String> keywords = new ArrayList<>();
        private String target;
        private String reply;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }

        IntentDefinition toDefinition() {
            IntentTarget parsed;
            try {
                parsed = IntentTarget.valueOf(target == null ? "" : target.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "intent '" + name + "' target must be answer, handoff or refuse, got: " + target);
            }
            return new IntentDefinition(
                    name == null ? null : name.trim(), description, keywords, parsed, reply);
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
