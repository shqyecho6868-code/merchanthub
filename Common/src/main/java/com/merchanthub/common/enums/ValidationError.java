package com.merchanthub.common.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * Message validation error enum
 *
 * Single place for all validation error codes and messages
 * Merges the former ValidationErrorCode, ValidationErrorMessage and ValidationRejectReason
 *
 * Usage:
 * - Error code: ValidationError.NOT_FRIEND.getCode() → 91002
 * - Message: ValidationError.NOT_FRIEND.getMessage() → "The recipient is not your friend"
 * - Identifier: ValidationError.NOT_FRIEND.name() → "NOT_FRIEND"
 * - Look up by code: ValidationError.fromCode(91002) → NOT_FRIEND
 * - Look up by name: ValidationError.fromName("NOT_FRIEND") → NOT_FRIEND
 */
public enum ValidationError {

    // ===================== Message validation error codes (91000-91099) =====================

    /**
     * Message validation failed (generic)
     */
    VALIDATION_FAILED(91001, "Message validation failed"),

    /**
     * The recipient is not your friend
     */
    NOT_FRIEND(91002, "The recipient is not your friend"),

    /**
     * You have been blocked by the recipient
     */
    BLOCKED_BY_RECEIVER(91003, "You have been blocked by the recipient"),

    /**
     * The friendship has been deleted
     */
    FRIEND_DELETED(91004, "The friendship has been deleted"),

    /**
     * You are not a member of this group
     */
    NOT_GROUP_MEMBER(91005, "You are not a member of this group"),

    /**
     * Sender account is disabled
     */
    SENDER_DISABLED(91006, "Sender account is disabled"),

    /**
     * Receiver account is disabled
     */
    RECEIVER_DISABLED(91007, "Receiver account is disabled"),

    /**
     * Service temporarily unavailable
     */
    SERVICE_UNAVAILABLE(91008, "Service temporarily unavailable, please try again later");


    /**
     * Error code
     */
    private final int code;

    /**
     * User-facing error message
     */
    private final String message;

    /**
     * Code-to-enum lookup cache, so fromCode doesn't scan every time
     */
    private static final Map<Integer, ValidationError> CODE_MAP;

    /**
     * Name-to-enum lookup cache
     */
    private static final Map<String, ValidationError> NAME_MAP;

    static {
        CODE_MAP = new HashMap<>();
        NAME_MAP = new HashMap<>();
        for (ValidationError e : values()) {
            CODE_MAP.put(e.code, e);
            NAME_MAP.put(e.name(), e);
        }
    }

    ValidationError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Look up the enum by error code
     *
     * @param code error code
     * @return the matching enum, or VALIDATION_FAILED if not found
     */
    public static ValidationError fromCode(int code) {
        return CODE_MAP.getOrDefault(code, VALIDATION_FAILED);
    }

    /**
     * Look up the enum by name (replaces ValidationRejectReason)
     *
     * @param name enum name (e.g. "NOT_FRIEND")
     * @return the matching enum, or VALIDATION_FAILED if not found
     */
    public static ValidationError fromName(String name) {
        if (name == null) {
            return VALIDATION_FAILED;
        }
        return NAME_MAP.getOrDefault(name, VALIDATION_FAILED);
    }
}