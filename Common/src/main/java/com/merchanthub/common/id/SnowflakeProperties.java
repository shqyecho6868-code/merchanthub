package com.merchanthub.common.id;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 雪花 ID 的 workerId 租约配置。
 * 续期间隔必须明显小于租约时长（这里默认 1/3），这样中间漏掉一两次续期也不会丢号。
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.snowflake")
public class SnowflakeProperties {

    /** Redis key 前缀，完整 key = 前缀 + 槽位号。所有服务共用一个命名空间，保证 order_no、pay_no 等全局不重复 */
    private String keyPrefix = "mh:snowflake:slot:";

    /** 租约时长（秒）：实例宕机后，最多过这么久号就会被回收 */
    private long leaseSeconds = 90;

    /** 续期间隔（秒） */
    private long renewIntervalSeconds = 30;
}
