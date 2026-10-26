package com.agentforge.channel.handoff;

/**
 * Hand-off behaviour on the channel side.
 *
 * @param notifyStaff a staff-group webhook is configured
 * @param replyWhenNotified what the user sees instead of the intent's reply when {@code notifyStaff}
 * @param excerptChars how much of the message is kept with the ticket; 0 keeps none
 */
public record HandoffSettings(boolean notifyStaff, String replyWhenNotified, int excerptChars) {

    /** No notice; excerpts on. */
    public static final HandoffSettings DEFAULT = new HandoffSettings(false, null, 120);

    public HandoffSettings {
        if (notifyStaff && (replyWhenNotified == null || replyWhenNotified.isBlank())) {
            throw new IllegalArgumentException("a notified hand-off needs the reply the user sees");
        }
        if (excerptChars < 0 || excerptChars > 200) {
            throw new IllegalArgumentException("excerpt chars must be between 0 and 200");
        }
    }
}
