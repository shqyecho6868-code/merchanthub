package com.merchanthub.common.constant;

public class CommonConstant {


    // D1: the JWT secret moved to config merchanthub.jwt.secret (see JwtCodec); token lifetimes are in JwtProperties

    public static final String ACCESS_TOKEN_PREFIX = "access:token:";

    public static final String REFRESH_TOKEN_PREFIX = "refresh:token:";

    public static final String KAFKA_MESSAGE_TOPIC_STORE = "store-topic";

    public static final String KAFKA_MESSAGE_TOPIC_PUSH = "message-topic";

    public static final String REDIS_NETTY_URI = "nettyUri";

    public static final String DISCOVERY_CLIENT_NAME = "RealTimeService";

    public static final String NETTY_SERVICE_URI = "/ws/netty";

    public static final String BUCKET_NAME = "infinitechat";

    public static final Integer PICTURE_EXPIRE_TIME = 3000;

    public static final String OFFLINE_KEY_REDIS = "user:offline:";

    public static final String SESSION_KEY_REDIS = "session:";

    public static final Long SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public static final Integer DEFAULT_LIMIT = 20;

    public static final Integer NETTY_SERVICE_PORT = 9101;

    public static final Long AI_ID = 111111111L;

    public static final Integer USER_ROLE_NORMAL = 2;

    public static final Integer SESSION_STATUS = 0;

    /**
     * Kafka topic for system notification messages
     */
    public static final String KAFKA_SYSTEM_NOTIFICATION_TOPIC = "system-notification-topic";

    /**
     * Kafka topic for persisting system notifications
     */
    public static final String KAFKA_STORE_NOTIFICATION_TOPIC = "store-notification-topic";
}