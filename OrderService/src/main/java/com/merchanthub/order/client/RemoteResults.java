package com.merchanthub.order.client;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 统一处理远程调用结果：
 * - 业务失败（body.code != 200）：原样透传下游的错误码和信息，比如“库存不足”
 * - 超时 / 连不上 / 5xx：转成 DOWNSTREAM_UNAVAILABLE（降级：快速失败，给用户明确提示，而不是抛一堆堆栈）
 * <p>
 * C2：每次调用包一层 Sentinel 资源（名字见 ProductInventoryClient.RES_*），熔断规则见 SentinelRulesConfig。
 * 下游连续慢 / 连续报错 → 熔断打开，接下来 N 秒<b>不再发请求</b>，直接降级返回。
 * 好处：order 的线程不会一个个卡在 3 秒超时上（否则下游一慢，order 也被拖垮），下游也有喘息时间恢复。
 * <p>
 * 没用 Spring Cloud Alibaba 的 feign.sentinel.enabled 自动集成：它会把<b>所有</b>异常（包括"库存不足"这类业务失败的
 * 解码异常）交给 fallback，资源名还是 "POST:http://product-inventory/internal/..." 这种长串；
 * 手写一层能精确控制"什么算下游故障"。
 */
@Slf4j
public final class RemoteResults {

    private static final int SUCCESS = 200;

    private RemoteResults() {
    }

    public static <T> T call(String resource, Supplier<BaseResponse<T>> call) {
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException e) {
            // 熔断中：连请求都不发，立刻降级
            log.warn("【熔断】{} 熔断中，快速失败", resource);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        BaseResponse<T> resp;
        try {
            resp = call.get();
        } catch (RetryableException e) {
            // 超时或连接失败：对方到底执行了没有，我们不知道（见 B1 笔记第 2 节“已知缺口”）
            Tracer.traceEntry(e, entry);
            log.warn("远程调用超时或连接失败: {}", resource, e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } catch (FeignException e) {
            // 只有 5xx / 没有状态码才算"下游故障"计入熔断统计；4xx 是我们自己的请求有问题，熔断下游没用
            if (e.status() < 0 || e.status() >= 500) {
                Tracer.traceEntry(e, entry);
            }
            log.warn("远程调用异常: {} status={}", resource, e.status(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } finally {
            // exit 时 Sentinel 记下这次调用的耗时，慢调用比例熔断靠它
            entry.exit();
        }
        if (resp == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (resp.getCode() != SUCCESS) {
            // 业务失败（库存不足等）说明下游是健康的，不计入熔断统计
            throw new BusinessException(resp.getCode(), resp.getMessage());
        }
        return resp.getData();
    }
}
