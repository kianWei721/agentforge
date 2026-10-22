package com.agentforge.channel.wecom;

/**
 * A decrypted smart-bot callback.
 *
 * @param chatId the group chat id; null in single chats
 * @param streamId set on stream refresh callbacks ({@code msgtype=stream}), which ask for the
 *     current content of a reply already started
 */
public record WeComInbound(
        String msgId,
        String botId,
        ChatType chatType,
        String chatId,
        String fromUserId,
        String msgType,
        String text,
        String streamId) {

    public enum ChatType {
        SINGLE,
        GROUP,
        /** Missing or a value we do not know; always refused. */
        UNKNOWN
    }

    public boolean isStreamRefresh() {
        return "stream".equals(msgType);
    }
}
