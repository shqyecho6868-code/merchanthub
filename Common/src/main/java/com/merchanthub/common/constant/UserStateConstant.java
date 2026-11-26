package com.merchanthub.common.constant;

/**
 * User state constants
 * <p>
 * Constant values for user account state
 * Removes magic numbers and unifies user state checks across services
 */
public final class UserStateConstant {

    private UserStateConstant() {
        // Utility class, not instantiable
    }

    // ===================== User state values =====================

    /**
     * User state: normal
     */
    public static final int STATE_NORMAL = 0;

    /**
     * User state: banned
     */
    public static final int STATE_BANNED = 1;

    /**
     * User state: cancelled (account closed)
     */
    public static final int STATE_CANCELLED = 2;

    // ===================== State descriptions =====================

    /**
     * Description for normal state
     */
    public static final String DESC_NORMAL = "正常";

    /**
     * Description for banned state
     */
    public static final String DESC_BANNED = "封禁";

    /**
     * Description for cancelled state
     */
    public static final String DESC_CANCELLED = "注销";

    /**
     * Description for a nonexistent user
     */
    public static final String DESC_NOT_EXIST = "用户不存在";

    /**
     * Description for an unknown state
     */
    public static final String DESC_UNKNOWN = "未知";

    /**
     * Get the state description for a state code
     *
     * @param state state code
     * @return state description
     */
    public static String getStateName(Integer state) {
        if (state == null) {
            return DESC_NOT_EXIST;
        }
        return switch (state) {
            case STATE_NORMAL -> DESC_NORMAL;
            case STATE_BANNED -> DESC_BANNED;
            case STATE_CANCELLED -> DESC_CANCELLED;
            default -> DESC_UNKNOWN;
        };
    }

}