package com.merchanthub.payment.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * order 客户端专用配置。<b>故意不加 @Configuration</b>（否则被扫描成全局配置），只通过 @FeignClient(configuration) 生效。
 * 不自动重试：读超时 ≠ 失败，重试会在下游最忙时加倍流量（理由同 order 的 ProductInventoryFeignConfig，C2 笔记 2.3）。
 * 建支付单失败用户可以再点；对账查询失败这一轮跳过，下一轮再查。
 */
public class OrderFeignConfig {

    @Bean
    public Retryer paymentOrderRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
