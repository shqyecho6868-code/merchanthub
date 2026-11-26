package com.merchanthub.common.enums;

/**
 * User session status enum
 * <p>
 * Values of the status column in the user_session table
 * Unifies user session status checks across services
 * <p>
 * Stored database values:
 * - 0: normal
 * - 1: deleted
 */
public enum UserSessionStatusEnum {

    /**
     * Status: normal
     */
    NORMAL(0, "Normal"),

    /**
     * Status: deleted
     */
    DELETED(1, "Deleted");

    private final int code;
    private final String description;

    UserSessionStatusEnum(int code, String description) {
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