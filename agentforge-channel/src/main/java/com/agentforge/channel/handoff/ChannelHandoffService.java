package com.agentforge.channel.handoff;

import com.agentforge.channel.ChannelMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What staff do with hand-offs: list, claim, resolve. Whoever acts is the caller's server-resolved
 * identity, never something in the request; that only staff call this is checked at the API.
 */
public final class ChannelHandoffService {

    static final int MAX_LIMIT = 100;
    static final int MAX_NOTE_CHARS = 500;

    private final ChannelHandoffRepository repository;
    private final Clock clock;
    private final ChannelMetrics metrics;

    public ChannelHandoffService(ChannelHandoffRepository repository, Clock clock, ChannelMetrics metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
    }

    public List<ChannelHandoff> list(HandoffStatus status, Instant createdAfter, int limit) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.list(status, createdAfter, limit);
    }

    public ChannelHandoff get(String id) {
        return repository.find(id).orElseThrow(() -> new ChannelHandoffNotFoundException(id));
    }

    public ChannelHandoff claim(String id, String staffUserId) {
        Instant now = clock.instant();
        if (!repository.claim(id, staffUserId, now)) {
            throw new ChannelHandoffInvalidStateException(get(id), "claim");
        }
        ChannelHandoff claimed = get(id);
        metrics.handoffClaimWait(Duration.between(claimed.createdAt(), now));
        return claimed;
    }

    public ChannelHandoff resolve(String id, String staffUserId, HandoffResolution resolution, String note) {
        if (resolution == null) {
            throw new IllegalArgumentException("resolution is required");
        }
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (trimmed != null && trimmed.codePointCount(0, trimmed.length()) > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("note must be at most " + MAX_NOTE_CHARS + " characters");
        }
        Instant now = clock.instant();
        if (!repository.resolve(id, staffUserId, resolution, trimmed, now)) {
            throw new ChannelHandoffInvalidStateException(get(id), "resolve");
        }
        ChannelHandoff resolved = get(id);
        metrics.handoffResolved(resolution.name(), Duration.between(resolved.createdAt(), now));
        return resolved;
    }
}
