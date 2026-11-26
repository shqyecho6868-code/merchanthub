package com.merchanthub.common.enums;

/**
 * Friend status enum
 *
 * Used during message validation to check the friend relationship
 * Shared friend status definition for UserService and RealTimeService
 *
 * Stored database values:
 * - 0: normal friendship
 * - 1: blocked
 * - 2: deleted
 */
public enum FriendStatusEnum {

    /**
     * Friend status: not friends
     * Special value used to cache "no friend record found"
     */
    NON_FRIEND(-1, "Not friends"),

    /**
     * Friend status: normal friendship
     */
    NORMAL(0, "Friends"),

    /**
     * Friend status: blocked
     */
    BLOCKED(1, "Blocked"),

    /**
     * Friend status: deleted
     */
    DELETED(2, "Deleted");

    private final int code;
    private final String description;

    FriendStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }



}