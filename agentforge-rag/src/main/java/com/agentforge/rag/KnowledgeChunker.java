package com.agentforge.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class KnowledgeChunker {

    private final int maxChars;
    private final int overlapChars;

    public KnowledgeChunker(int maxChars, int overlapChars) {
        if (maxChars < 200) {
            throw new IllegalArgumentException("maxChars must be >= 200");
        }
        if (overlapChars < 0 || overlapChars >= maxChars / 2) {
            throw new IllegalArgumentException("overlapChars must be >= 0 and < maxChars/2");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<KnowledgeChunk> chunk(String documentId, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length()) {
            int targetEnd = Math.min(normalized.length(), start + maxChars);
            int end = chooseBoundary(normalized, start, targetEnd);
            String text = normalized.substring(start, end).trim();
            if (!text.isEmpty()) {
                chunks.add(
                        new KnowledgeChunk(
                                UUID.randomUUID().toString(),
                                documentId,
                                index++,
                                text,
                                text.length()));
            }
            if (end >= normalized.length()) {
                break;
            }
            int next = Math.max(start + 1, end - overlapChars);
            start = next;
        }
        return List.copyOf(chunks);
    }

    private int chooseBoundary(String text, int start, int targetEnd) {
        if (targetEnd >= text.length()) {
            return text.length();
        }
        int minBoundary = start + maxChars / 2;
        for (int i = targetEnd - 1; i >= minBoundary; i--) {
            char c = text.charAt(i);
            if (c == '\n'
                    || c == '。'
                    || c == '！'
                    || c == '？'
                    || c == '.'
                    || c == '!'
                    || c == '?'
                    || c == '；'
                    || c == ';') {
                return i + 1;
            }
        }
        return targetEnd;
    }
}
