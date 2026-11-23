package com.merchanthub.order.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * product-inventory 这个 Feign 客户端专用配置（C2）。<b>故意不加 @Configuration</b>：
 * 加了会被组件扫描成全局配置，影响所有 Feign 客户端；只通过 @FeignClient(configuration = ...) 生效。
 */
public class ProductInventoryFeignConfig {

    /**
     * 明确不自动重试（Spring Cloud OpenFeign 默认也是 NEVER_RETRY，这里显式写出来，防止以后有人"顺手"打开）。
     * <p>
     * 预占接口本身<b>是幂等的</b>（按 orderNo，重复请求直接返回成功），重试不会重复扣库存，但仍然不能自动重试：
     * <ol>
     *   <li><b>读超时 ≠ 失败</b>：3 秒没返回，下游多半是慢，不是没做。这时再发一次，等于在下游最忙的时候给它加倍的活；
     *       每个上游都这么干，流量变成 2 倍、3 倍（重试风暴），下游从"慢"变成"挂"</li>
     *   <li><b>拖长用户等待</b>：单次读超时 3 秒，重试 2 次就是 9 秒。用户早就走了，下游还在白白处理</li>
     *   <li><b>幂等只在 orderNo 不变时成立</b>：自动重试能保证同一个 orderNo；可一旦有人在 service 层"失败就重新下单"，
     *       新 orderNo 就是一笔新的预占。所以"能不能重试"要在业务层看清楚再决定，不交给框架默默做</li>
     * </ol>
     * 超时后的真实结果由 B3 的悬空预占扫描兜底（有预占、没订单 → 释放），用户看到"稍后重试"。
     * 查 SKU 是只读调用，理论上可以重试，但同样受第 1、2 条约束；真要重试也应只重试"连接失败"（请求根本没发出去），
     * 并带退避，这是将来的改进项。
     */
    @Bean
    public Retryer productInventoryRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
