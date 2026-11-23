package com.merchanthub.common.exception;


import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * <p>
 * 只在 Servlet 类型的 Web 应用中生效：Gateway 是 WebFlux 应用，不应加载它。
 * AI 护栏异常不在这里处理（避免 Common 依赖 LangChain4j），见 ai 模块的 AiExceptionHandler。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务失败（库存不足、订单不存在……）是"预期内的结果"，不是系统故障：记 WARN、不打堆栈（C1 遗留，C2 修）。
     * 记成 ERROR 的话，按 ERROR 数告警的规则会被正常业务失败刷屏，真正的故障反而被淹没
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.warn("业务失败 code={} message={}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 被限流 → HTTP 429 + Retry-After（C2）。不能落到 RuntimeException 兜底变成 500：
     * 500 会被监控算成"服务故障"，客户端也分不清是"稍后再试就行"还是"系统坏了"
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<BaseResponse<?>> tooManyRequestsHandler(TooManyRequestsException e) {
        log.warn("请求被限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    /**
     * 越权 → HTTP 403（C1）。和其他业务错误不同，这里用真正的 HTTP 状态码：
     * 网关、监控、Sentinel 都按状态码统计，"被拒绝的越权请求"要能和"正常业务失败"区分开。
     * Spring 按异常类型就近匹配，ForbiddenException 虽然是 BusinessException 的子类，也会走这里
     */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public BaseResponse<?> forbiddenHandler(ForbiddenException e) {
        log.warn("越权访问被拒绝: {}", e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public BaseResponse<?> unauthorizedHandler(UnauthorizedException e) {
        log.warn("身份校验失败: {}", e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求体不是合法 JSON / 字段类型对不上（如数量传了 "abc"）→ HTTP 400。
     * 之前被下面的 RuntimeException 兜底成 500"系统错误"（P23）：客户端的错被算成服务端故障，告警误报、调用方也不知道该改请求
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<?> notReadableHandler(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误");
    }

    /** 缺必填请求头 / 路径参数类型不对（如订单号传了字母）→ 400，同属客户端错误 */
    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<?> badRequestHandler(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求参数错误");
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(fieldName, message);
        });
        String message = errors.toString();
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, message);
    }


    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<?> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream().map(ConstraintViolation::getMessage).findFirst().orElse("请求参数校验失败");
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public BaseResponse<?> handlerMissingServletRequestParameterException(Exception e) {
        log.error("缺少必填参数:{}", e.toString());
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, "缺少必填参数");
    }
}