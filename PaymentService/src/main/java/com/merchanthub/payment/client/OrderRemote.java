package com.merchanthub.payment.client;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * payment → order 的调用包一层 Sentinel 熔断（E3，补 C2 遗留）。做法与 order 的 RemoteResults 一致：
 * <ul>
 *   <li>熔断打开：连请求都不发，立刻抛 DOWNSTREAM_UNAVAILABLE —— payment 的线程不会一个个卡在 3 秒超时上</li>
 *   <li>超时 / 连不上 / 5xx 才计入熔断统计；4xx、业务失败（订单不存在）说明下游是健康的，不计入</li>
 *   <li>业务失败原样透传下游的错误码</li>
 * </ul>
 * 熔断规则见 {@link com.merchanthub.payment.config.PaymentSentinelConfig}。
 */
@Slf4j
public final class OrderRemote {

    private static final int SUCCESS = 200;

    private OrderRemote() {
    }

    public static <T> T call(String resource, Supplier<BaseResponse<T>> call) {
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException e) {
            log.warn("【熔断】{} 熔断中，快速失败", resource);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        BaseResponse<T> resp;
        try {
            resp = call.get();
        } catch (FeignException e) {
            // RetryableException（超时 / 连接失败）的 status 是 -1，也走这里
            if (e.status() < 0 || e.status() >= 500) {
                Tracer.traceEntry(e, entry);
            }
            log.warn("调用 order 失败: {} status={}", resource, e.status(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } finally {
            entry.exit();
        }
        if (resp == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (resp.getCode() != SUCCESS) {
            throw new BusinessException(resp.getCode(), resp.getMessage());
        }
        return resp.getData();
    }
}
