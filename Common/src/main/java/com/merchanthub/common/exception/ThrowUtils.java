package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/**
 * Helpers for throwing exceptions
 */
public class ThrowUtils {

    /**
     * Throw if the condition holds
     *
     * @param condition the condition; throws if {@code true}
     * @param runtimeException the runtime exception to throw
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * Throw if the condition holds
     *
     * @param condition the condition; throws if {@code true}
     * @param errorCode business error code used to build the {@link BusinessException}
     */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /**
     * Throw if the condition holds
     *
     * @param condition the condition; throws if {@code true}
     * @param errorCode business error code
     * @param message custom exception message
     */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}

