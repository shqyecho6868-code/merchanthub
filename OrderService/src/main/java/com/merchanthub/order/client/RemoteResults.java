package com.merchanthub.order.client;

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
 */
@Slf4j
public final class RemoteResults {

    private static final int SUCCESS = 200;

    private RemoteResults() {
    }

    public static <T> T call(String what, Supplier<BaseResponse<T>> call) {
        BaseResponse<T> resp;
        try {
            resp = call.get();
        } catch (RetryableException e) {
            // 超时或连接失败：对方到底执行了没有，我们不知道（见 B1 笔记第 2 节“已知缺口”）
            log.warn("远程调用超时或连接失败: {}", what, e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } catch (FeignException e) {
            log.warn("远程调用异常: {} status={}", what, e.status(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
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
