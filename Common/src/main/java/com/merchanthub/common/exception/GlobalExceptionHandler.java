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
 * Global exception handler
 * <p>
 * Only active in Servlet web apps: the Gateway is a WebFlux app and must not load it.
 * AI guardrail exceptions are not handled here (to keep Common free of LangChain4j); see AiExceptionHandler in the ai module.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Business failures (insufficient stock, order not found...) are expected outcomes, not system faults: log at WARN without a stack trace.
     * Logging them at ERROR would flood ERROR-count alerts with normal business failures and bury the real incidents
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.warn("Business failure code={} message={}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * Rate limited → HTTP 429 + Retry-After. Must not fall through to the RuntimeException catch-all and become a 500:
     * monitoring counts 500 as a service fault, and clients can't tell "just retry later" from "the system is broken"
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<BaseResponse<?>> tooManyRequestsHandler(TooManyRequestsException e) {
        log.warn("Request rate limited: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ResultUtils.error(e.getCode(), e.getMessage()));
    }

    /**
     * Access denied → HTTP 403. Unlike other business errors, this uses a real HTTP status code:
     * the gateway, monitoring and Sentinel all count by status code, so rejected unauthorized requests must be distinguishable from normal business failures.
     * Spring matches the closest exception type, so ForbiddenException lands here even though it extends BusinessException
     */
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public BaseResponse<?> forbiddenHandler(ForbiddenException e) {
        log.warn("Unauthorized access rejected: {}", e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public BaseResponse<?> unauthorizedHandler(UnauthorizedException e) {
        log.warn("Authentication failed: {}", e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * Request body is not valid JSON / a field has the wrong type (e.g. quantity sent as "abc") → HTTP 400.
     * Previously this fell through to the RuntimeException catch-all as a 500 "system error": a client mistake counted as a server fault, causing false alerts and leaving the caller no hint to fix the request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<?> notReadableHandler(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "Malformed request body");
    }

    /** Missing required header / wrong path variable type (e.g. letters in an order number) → 400, also a client error */
    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<?> badRequestHandler(Exception e) {
        log.warn("Invalid request parameters: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "Invalid request parameters");
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "System error");
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
        String message = e.getConstraintViolations().stream().map(ConstraintViolation::getMessage).findFirst().orElse("Request parameter validation failed");
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public BaseResponse<?> handlerMissingServletRequestParameterException(Exception e) {
        log.error("Missing required parameter: {}", e.toString());
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, "Missing required parameter");
    }
}