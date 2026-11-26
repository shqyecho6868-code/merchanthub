package com.merchanthub.common.constant;
/**
 * Message type constants
 *
 * Message type ranges:
 * - 0-99: chat messages (text, image, sticker, etc.)
 * - 100-199: system notifications (friend requests, new sessions, etc.)
 *
 * This class defines the system notification types (range 100-199)
 */
public class MessageTypeConstant {

    public static final int TEXT_MESSAGE = 0;

    public static final int IMAGE_MESSAGE = 1;

    public static final int EMOJI_MESSAGE = 2;

    /**
     * System notification: friend request received
     * Scenario: user A sends user B a friend request
     */
    public static final int TYPE_SYSTEM_NEW_APPLY = 101;

    /**
     * System notification: new session created
     * Scenario: after users A and B become friends, the system creates a one-to-one session
     */
    public static final int TYPE_SYSTEM_NEW_SESSION = 102;

    /**
     * System notification: new group session created (group invitation)
     * Scenario: a user is invited to a group chat
     */
    public static final int TYPE_SYSTEM_NEW_GROUP_SESSION = 103;

    /**
     * System notification: removed from group chat
     * Scenario: a user is removed from a group chat; all members (including the removed user) are notified
     */
    public static final int TYPE_SYSTEM_GROUP_KICK = 104;

    /**
     * Chat message type range: 0-99
     */
    public static final int CHAT_MESSAGE_MIN = 0;
    public static final int CHAT_MESSAGE_MAX = 99;

    /**
     * System notification type range: 100-199
     */
    public static final int SYSTEM_NOTIFICATION_MIN = 100;
    public static final int SYSTEM_NOTIFICATION_MAX = 199;

    /**
     * Whether the type is a system notification
     *
     * @param type message type
     * @return true if it is a system notification type
     */
    public static boolean isSystemNotification(int type) {
        return type >= SYSTEM_NOTIFICATION_MIN && type <= SYSTEM_NOTIFICATION_MAX;
    }

    /**
     * Whether the type is a chat message
     *
     * @param type message type
     * @return true if it is a chat message type
     */
    public static boolean isChatMessage(int type) {
        return type >= CHAT_MESSAGE_MIN && type <= CHAT_MESSAGE_MAX;
    }
}