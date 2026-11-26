package com.merchanthub.order.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Dedicated config for the product-inventory Feign client. <b>Intentionally not annotated with @Configuration</b>:
 * otherwise component scanning would pick it up as global config affecting all Feign clients; it only applies via @FeignClient(configuration = ...).
 */
public class ProductInventoryFeignConfig {

    /**
     * Explicitly no automatic retry (Spring Cloud OpenFeign defaults to NEVER_RETRY too; stated explicitly so nobody "conveniently" turns it on later).
     * <p>
     * The reserve API itself <b>is idempotent</b> (keyed by orderNo; duplicate requests just return success), so retries won't double-deduct, but we still don't auto-retry:
     * <ol>
     *   <li><b>Read timeout != failure</b>: no response in 3s usually means the downstream is slow, not that it didn't do the work. Sending again doubles its load exactly when it's busiest;
     *       if every upstream does that, traffic becomes 2x, 3x (retry storm), and the downstream goes from "slow" to "down"</li>
     *   <li><b>Longer user wait</b>: a single read timeout is 3s; 2 retries make it 9s. The user is long gone while the downstream keeps doing wasted work</li>
     *   <li><b>Idempotency holds only while orderNo stays the same</b>: automatic retry keeps the same orderNo, but if someone adds "re-place the order on failure" in the service layer,
     *       the new orderNo is a brand-new reservation. So "can we retry" must be decided deliberately in the business layer, not silently by the framework</li>
     * </ol>
     * The real outcome after a timeout is covered by the dangling-reservation scan (reservation without an order -> release); the user sees "try again later".
     * Fetching SKUs is a read-only call and could in theory be retried, but points 1 and 2 still apply; if we ever retry, it should be only on "connection failure" (request never sent),
     * with backoff -- a future improvement.
     */
    @Bean
    public Retryer productInventoryRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
