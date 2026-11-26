package com.merchanthub.payment.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Config dedicated to the order client. <b>Deliberately not annotated with @Configuration</b> (otherwise it would be scanned as global config); it only takes effect via @FeignClient(configuration).
 * No automatic retry: a read timeout ≠ failure, and retrying doubles traffic exactly when the downstream is busiest (same reasoning as order's ProductInventoryFeignConfig.
 * If creating a payment fails the user can click again; if a reconciliation query fails, this round is skipped and retried next round.
 */
public class OrderFeignConfig {

    @Bean
    public Retryer paymentOrderRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
