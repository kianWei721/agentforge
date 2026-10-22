package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WeComSessionKeysTest {

    @Test
    void chatsDifferingOnlyInCaseGetDifferentSessions() {
        // af_session.session_key is case-insensitive; raw ids would collide there.
        assertNotEquals(
                WeComSessionKeys.group("bot", "wrkAbc").toLowerCase(),
                WeComSessionKeys.group("bot", "wrkABC").toLowerCase());
    }

    @Test
    void directAndGroupSessionsNeverCollide() {
        assertNotEquals(WeComSessionKeys.direct("bot", "same"), WeComSessionKeys.group("bot", "same"));
        assertNotEquals(WeComSessionKeys.direct("bot-a", "u"), WeComSessionKeys.direct("bot-b", "u"));
        // Length prefixes keep ("ab", "c") and ("a", "bc") apart.
        assertNotEquals(WeComSessionKeys.direct("ab", "c"), WeComSessionKeys.direct("a", "bc"));
    }

    @Test
    void keysFitTheSessionColumnAndAreRecognisable() {
        String key = WeComSessionKeys.direct("bot", "x".repeat(500));

        assertTrue(key.length() <= 128);
        assertTrue(WeComSessionKeys.isChannelSession(key));
        assertTrue(WeComSessionKeys.isChannelSession("  wecom:anything"));
        // The session column ignores case, so the check must too.
        assertTrue(WeComSessionKeys.isChannelSession("WECOM:anything"));
        assertFalse(WeComSessionKeys.isChannelSession("my-session"));
        assertFalse(WeComSessionKeys.isChannelSession(null));
    }

    @Test
    void aRedeliveredMessageGetsTheSameStream() {
        assertEquals(WeComSessionKeys.streamId("bot", "m1"), WeComSessionKeys.streamId("bot", "m1"));
        assertNotEquals(WeComSessionKeys.streamId("bot", "m1"), WeComSessionKeys.streamId("bot", "M1"));
    }
}
