package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LexicalKnowledgeRetriever implements KnowledgeRetriever {

    private static final int MAX_CANDIDATES = 5000;

    private final KnowledgeRepository repository;

    public LexicalKnowledgeRetriever(KnowledgeRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query, int topK, AccessPrincipal principal) {
        KnowledgeAccessPolicy.requirePrincipal(principal);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }

        String normalizedQuery = normalize(query);
        Set<String> queryTokens = tokens(normalizedQuery);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (KnowledgeCandidate candidate :
                repository.findAccessibleCandidates(MAX_CANDIDATES, principal)) {
            // The repository filters in SQL; re-checking the row's own ACL catches a wrong predicate.
            if (!KnowledgeAccessPolicy.permits(principal, candidate.access())) {
                continue;
            }
            String normalizedContent = normalize(candidate.content());
            Set<String> contentTokens = tokens(normalizedContent);
            double score = cosineLikeOverlap(queryTokens, contentTokens);

            Set<String> titleTokens = tokens(normalize(candidate.title()));
            score += 0.35d * overlapRatio(queryTokens, titleTokens);
            if (normalizedContent.contains(normalizedQuery)) {
                score += 1.0d;
            }
            if (score > 0d) {
                scored.add(new ScoredCandidate(candidate, score));
            }
        }

        return scored.stream()
                .sorted(
                        Comparator.comparingDouble(ScoredCandidate::score)
                                .reversed()
                                .thenComparing(sc -> sc.candidate().documentId())
                                .thenComparingInt(sc -> sc.candidate().chunkIndex()))
                .limit(topK)
                .map(
                        scoredCandidate -> {
                            KnowledgeCandidate c = scoredCandidate.candidate();
                            return new KnowledgeSearchHit(
                                    "KB:" + c.documentId() + "#" + c.chunkIndex(),
                                    c.documentId(),
                                    c.chunkId(),
                                    c.chunkIndex(),
                                    c.title(),
                                    c.sourceType(),
                                    c.sourceRef(),
                                    c.content(),
                                    scoredCandidate.score(),
                                    "LEXICAL",
                                    null,
                                    null);
                        })
                .toList();
    }

    @Override
    public String mode() {
        return "LEXICAL_V1";
    }

    private static double cosineLikeOverlap(Set<String> query, Set<String> content) {
        if (query.isEmpty() || content.isEmpty()) {
            return 0d;
        }
        int intersection = 0;
        for (String token : query) {
            if (content.contains(token)) {
                intersection++;
            }
        }
        return intersection / Math.sqrt((double) query.size() * content.size());
    }

    private static double overlapRatio(Set<String> query, Set<String> candidate) {
        if (query.isEmpty()) {
            return 0d;
        }
        int intersection = 0;
        for (String token : query) {
            if (candidate.contains(token)) {
                intersection++;
            }
        }
        return (double) intersection / query.size();
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                flushAscii(ascii, result);
                han.append(c);
                continue;
            }
            flushHan(han, result);
            if (Character.isLetterOrDigit(c)) {
                ascii.append(c);
            } else {
                flushAscii(ascii, result);
            }
        }
        flushAscii(ascii, result);
        flushHan(han, result);
        return result;
    }

    private static void flushAscii(StringBuilder value, Set<String> target) {
        if (value.length() > 0) {
            target.add("w:" + value);
            value.setLength(0);
        }
    }

    private static void flushHan(StringBuilder value, Set<String> target) {
        if (value.length() == 0) {
            return;
        }
        String run = value.toString();
        for (int i = 0; i < run.length(); i++) {
            target.add("c:" + run.charAt(i));
            if (i + 1 < run.length()) {
                target.add("g:" + run.substring(i, i + 2));
            }
        }
        value.setLength(0);
    }

    private static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private record ScoredCandidate(KnowledgeCandidate candidate, double score) {}
}
