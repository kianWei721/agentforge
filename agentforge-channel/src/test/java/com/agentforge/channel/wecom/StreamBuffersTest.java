package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StreamBuffersTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T00:00:00Z"));
    private final StreamBuffers buffers = new StreamBuffers(Duration.ofMinutes(10), clock);

    @Test
    void snapshotsTheWholeAnswerSoFar() {
        buffers.open("s1");
        buffers.append("s1", "小规模");
        buffers.append("s1", "纳税人");

        WeComStreamReply partial = buffers.snapshot("s1").orElseThrow();
        assertEquals("小规模纳税人", partial.content());
        assertFalse(partial.finished());

        buffers.finish("s1");
        assertTrue(buffers.snapshot("s1").orElseThrow().finished());
    }

    @Test
    void cutsOnACharacterBoundaryWithinWeComsByteLimit() {
        buffers.open("s1");
        // 3-byte characters and a 4-byte emoji so a byte-level cut would split one.
        buffers.append("s1", "税".repeat(7000) + "😀".repeat(100));

        String content = buffers.snapshot("s1").orElseThrow().content();

        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= StreamBuffers.MAX_CONTENT_BYTES);
        assertTrue(content.endsWith(StreamBuffers.TRUNCATED_NOTICE));
        assertFalse(content.contains("�"));
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        assertEquals("a", StreamBuffers.truncateUtf8("a😀", 4));
        assertEquals("a😀", StreamBuffers.truncateUtf8("a😀", 5));
        assertEquals("", StreamBuffers.truncateUtf8("税", 2));
    }

    @Test
    void failureKeepsWhatWasShownAndEndsTheStream() {
        buffers.open("s1");
        buffers.append("s1", "部分回答");
        buffers.fail("s1", "回答超时了");

        WeComStreamReply reply = buffers.snapshot("s1").orElseThrow();
        assertEquals("部分回答\n\n回答超时了", reply.content());
        assertTrue(reply.finished());
    }

    @Test
    void aFailureNoticeAfterALongAnswerStaysVisible() {
        buffers.open("s1");
        buffers.append("s1", "税".repeat(7000));
        buffers.fail("s1", "回答超时了");

        String content = buffers.snapshot("s1").orElseThrow().content();

        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= StreamBuffers.MAX_CONTENT_BYTES);
        assertTrue(content.endsWith(StreamBuffers.TRUNCATED_NOTICE + "\n\n回答超时了"));
        assertTrue(content.contains(StreamBuffers.TRUNCATED_NOTICE));
    }

    @Test
    void finishedRefusalIsServedAsIs() {
        buffers.openFinished("s1", "你还没有开通这个助手");

        assertEquals(
                new WeComStreamReply("s1", "你还没有开通这个助手", true),
                buffers.snapshot("s1").orElseThrow());
    }

    @Test
    void expiredStreamsAreGoneAndEvicted() {
        buffers.open("s1");
        clock.advance(Duration.ofMinutes(11));

        assertTrue(buffers.snapshot("s1").isEmpty());
        buffers.open("s2");
        assertEquals(1, buffers.size());
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
