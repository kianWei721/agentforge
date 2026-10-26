package com.agentforge.channel.handoff;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What staff see of the customer's message: long numbers (phone, ID card, bank card) masked, then
 * cut. A number is 11 to 19 digits, ASCII or full-width, possibly grouped by single spaces or
 * hyphens ("138 1234 5678"), the last one possibly an X; it keeps its first three and last four.
 * Names and addresses are not recognised.
 */
public final class ExcerptMasker {

    private static final String DIGIT = "[0-9０-９]";
    private static final Pattern LONG_NUMBER =
            Pattern.compile("(?<![0-9０-９])" + DIGIT + "(?:[ -]?" + DIGIT + "){9,17}[ -]?[0-9０-９Xx](?![0-9０-９Xx])");

    private ExcerptMasker() {}

    /** @return null when {@code maxChars} is 0: excerpts are off */
    public static String excerpt(String text, int maxChars) {
        if (maxChars <= 0 || text == null) {
            return null;
        }
        Matcher matcher = LONG_NUMBER.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String number = matcher.group().replaceAll("[ -]", "");
            matcher.appendReplacement(
                    masked,
                    Matcher.quoteReplacement(
                            number.substring(0, 3) + "*".repeat(number.length() - 7)
                                    + number.substring(number.length() - 4)));
        }
        matcher.appendTail(masked);
        return cut(masked.toString().strip(), maxChars);
    }

    /** At most {@code maxChars} code points, never half a surrogate pair. */
    static String cut(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars)) + "…";
    }
}
