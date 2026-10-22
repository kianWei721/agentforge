package com.agentforge.channel.wecom;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers in progress, keyed by stream id. WeCom pulls them with stream refresh callbacks; each
 * snapshot is the full text so far, cut on a UTF-8 character boundary to WeCom's limit.
 */
public final class StreamBuffers {

    /** WeCom's limit for {@code stream.content}, in UTF-8 bytes. */
    public static final int MAX_CONTENT_BYTES = 20480;

    static final String TRUNCATED_NOTICE = "\n\n（内容过长，已截断）";

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public StreamBuffers(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public void open(String streamId) {
        evictExpired();
        entries.putIfAbsent(streamId, new Entry(clock.instant().plus(ttl)));
    }

    /** Opens an already finished stream, e.g. a fixed refusal. */
    public void openFinished(String streamId, String content) {
        evictExpired();
        Entry entry = new Entry(clock.instant().plus(ttl));
        entry.append(content);
        entry.finish();
        entries.putIfAbsent(streamId, entry);
    }

    public void append(String streamId, String delta) {
        Entry entry = entries.get(streamId);
        if (entry != null && delta != null) {
            entry.append(delta);
        }
    }

    public void finish(String streamId) {
        Entry entry = entries.get(streamId);
        if (entry != null) {
            entry.finish();
        }
    }

    /** Ends the stream with an explanation, keeping whatever was already shown. */
    public void fail(String streamId, String notice) {
        Entry entry = entries.get(streamId);
        if (entry != null) {
            entry.fail(notice);
        }
    }

    public Optional<WeComStreamReply> snapshot(String streamId) {
        Entry entry = entries.get(streamId);
        if (entry == null || entry.expiresAt.isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot(streamId));
    }

    int size() {
        return entries.size();
    }

    private void evictExpired() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> entry.expiresAt.isBefore(now));
    }

    /** The longest prefix of {@code text} that fits in {@code maxBytes} of UTF-8. */
    static String truncateUtf8(String text, int maxBytes) {
        int bytes = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int size =
                    codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return text.substring(0, index);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * One answer. Text, notice and the finished flag change and are read under one lock, so a
     * snapshot never shows "finished" without the last text that came before it.
     */
    private static final class Entry {
        private final StringBuilder text = new StringBuilder();
        private final Instant expiresAt;
        private String notice;
        private boolean finished;

        private Entry(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        private synchronized void append(String delta) {
            // Every char is at least one UTF-8 byte, so text beyond this is cut anyway.
            if (text.length() <= MAX_CONTENT_BYTES) {
                text.append(delta);
            }
        }

        private synchronized void finish() {
            finished = true;
        }

        private synchronized void fail(String failureNotice) {
            notice = failureNotice;
            finished = true;
        }

        private synchronized WeComStreamReply snapshot(String streamId) {
            return new WeComStreamReply(streamId, content(), finished);
        }

        /** The answer, cut if needed so that a failure notice after it always stays visible. */
        private String content() {
            String body = text.toString();
            String tail = notice == null ? "" : (body.isEmpty() ? "" : "\n\n") + notice;
            if (utf8Length(body) + utf8Length(tail) <= MAX_CONTENT_BYTES) {
                return body + tail;
            }
            int room = MAX_CONTENT_BYTES - utf8Length(tail) - utf8Length(TRUNCATED_NOTICE);
            return truncateUtf8(body, room) + TRUNCATED_NOTICE + tail;
        }
    }
}
