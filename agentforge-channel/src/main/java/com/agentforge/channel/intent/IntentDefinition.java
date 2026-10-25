package com.agentforge.channel.intent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One configured intent.
 *
 * @param name the label written to the inbound table, to metrics and to the evaluation set, so it
 *     is chosen once: a lower case letter, then lower case letters and underscores
 * @param description what belongs here; the classifier reads it
 * @param keywords phrases that decide this intent as soon as the message arrives, matched as
 *     plain substrings (never patterns: they run on the callback thread)
 * @param reply what the user sees for HANDOFF and REFUSE; none for ANSWER
 */
public record IntentDefinition(
        String name, String description, List<String> keywords, IntentTarget target, String reply) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z_]{0,31}");

    public IntentDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("intent name must match [a-z][a-z_]{0,31}, got: " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("intent '" + name + "' needs a description");
        }
        if (target == null) {
            throw new IllegalArgumentException("intent '" + name + "' needs a target");
        }
        keywords =
                keywords == null
                        ? List.of()
                        : keywords.stream().map(k -> k == null ? "" : k.trim().toLowerCase(Locale.ROOT)).toList();
        if (keywords.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("intent '" + name + "' has a blank keyword");
        }
        reply = reply == null || reply.isBlank() ? null : reply.trim();
        if (target == IntentTarget.ANSWER && reply != null) {
            throw new IllegalArgumentException("intent '" + name + "' is answered by the agent; it takes no reply");
        }
        if (target != IntentTarget.ANSWER && reply == null) {
            throw new IllegalArgumentException("intent '" + name + "' needs the reply the user sees");
        }
    }

    boolean matches(String lowerCaseText) {
        for (String keyword : keywords) {
            if (lowerCaseText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
