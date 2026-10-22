package com.agentforge.runtime;

/**
 * Sessions owned by a messaging channel. The channel is their only writer: it serializes turns
 * per session, and a second writer over HTTP would interleave turns and race on agent state.
 *
 * <p>The prefix check ignores case because {@code af_session.session_key} uses a case- and
 * accent-insensitive collation: {@code WECOM:...} opens the same row as {@code wecom:...}.
 */
public final class ChannelSessions {

    public static final String WECOM_PREFIX = "wecom:";
    public static final String WECOM_SOURCE = "wecom";

    private ChannelSessions() {}

    public static boolean isChannelSession(String sessionKey) {
        if (sessionKey == null) {
            return false;
        }
        String key = sessionKey.trim();
        return key.regionMatches(true, 0, WECOM_PREFIX, 0, WECOM_PREFIX.length());
    }

    /** @throws IllegalArgumentException when a source other than the channel writes its session */
    public static void requireWriter(String sessionKey, String source) {
        if (isChannelSession(sessionKey) && !WECOM_SOURCE.equals(source)) {
            throw new IllegalArgumentException(
                    "session belongs to the WeCom channel; the API may read it but not write to it");
        }
    }
}
