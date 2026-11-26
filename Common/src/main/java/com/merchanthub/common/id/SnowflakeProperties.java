package com.merchanthub.common.id;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * workerId lease settings for Snowflake IDs.
 * The renewal interval must be well below the lease duration (1/3 by default) so missing one or two renewals doesn't lose the slot.
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.snowflake")
public class SnowflakeProperties {

    /** Redis key prefix; full key = prefix + slot number. All services share one namespace so order_no, pay_no etc. are globally unique */
    private String keyPrefix = "mh:snowflake:slot:";

    /** Lease duration (seconds): after an instance crashes, its slot is reclaimed within this time */
    private long leaseSeconds = 90;

    /** Renewal interval (seconds) */
    private long renewIntervalSeconds = 30;
}
