package com.agentforge.channel.handoff;

/** Posts one notice to the staff group. */
public interface HandoffNotificationSender {

    /**
     * @param rateLimited the receiver asked us to slow down; nothing is wrong with the notice
     * @param error what failed, never the webhook address (it holds the key)
     */
    record Result(boolean sent, boolean rateLimited, String error) {

        public static final Result SENT = new Result(true, false, null);

        public static Result failed(String error) {
            return new Result(false, false, error);
        }

        public static Result rateLimited(String error) {
            return new Result(false, true, error);
        }
    }

    /** Never throws: every failure is a result. */
    Result send(String text);
}
