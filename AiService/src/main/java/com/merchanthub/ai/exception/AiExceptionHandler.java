package com.merchanthub.ai.exception;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import dev.langchain4j.guardrail.InputGuardrailException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 服务专属异常处理
 * <p>
 * 从 Common 的 GlobalExceptionHandler 挪过来：LangChain4j 只有 ai 服务需要，
 * 留在 Common 会让所有服务都被迫依赖 LangChain4j。
 * <p>
 * 必须最高优先级：InputGuardrailException 也是 RuntimeException 的子类，
 * 如果 GlobalExceptionHandler 先匹配，会被当成普通运行时异常返回“系统错误”。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AiExceptionHandler {

    @ExceptionHandler(InputGuardrailException.class)
    public BaseResponse<?> inputGuardrailExceptionHandler(InputGuardrailException e) {
        log.warn("敏感词拦截: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.SENSITIVE_WORD_ERROR);
    }
}
