package com.merchanthub.common.constant;

/**
 * Redis key constants for message validation
 *
 * Redis key prefixes and cache settings for message validation
 * Single place for these Redis key definitions across services
 */
public final class ValidationRedisKey {

    private ValidationRedisKey() {
        // Utility class, not instantiable
    }

    // ===================== Redis key prefixes =====================

    /**
     * Cache key prefix for friend status
     * Full format: msg:validate:friend:status:{userId}:{friendId}
     */
    public static final String FRIEND_STATUS_KEY_PREFIX = "msg:validate:friend:status:";

    // ===================== Cache values =====================

    /**
     * Cache value for "not a friend"
     * Distinguishes "looked up and not a friend" from "cache miss"
     */
    public static final String NON_FRIEND_CACHE_VALUE = "-1";

    // ===================== Cache TTL =====================

    /**
     * Friend relationship cache TTL (minutes)
     */
    public static final long FRIEND_STATUS_TTL_MINUTES = 5;

    // ===================== Utility methods =====================

    /**
     * Build the friend status cache key
     *
     * @param userId   user ID
     * @param friendId friend ID
     * @return the full Redis key
     */
    public static String buildFriendStatusKey(Long userId, Long friendId) {
        return FRIEND_STATUS_KEY_PREFIX + userId + ":" + friendId;
    }

    /**
     * Whether the cached value means "not a friend"
     *
     * @param cachedValue cached value
     * @return whether it means "not a friend"
     */
    public static boolean isNonFriendValue(String cachedValue) {
        return NON_FRIEND_CACHE_VALUE.equals(cachedValue);
    }
}