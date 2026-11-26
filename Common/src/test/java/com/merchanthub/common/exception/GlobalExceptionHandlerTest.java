package com.merchanthub.common.exception;

import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test example (A2+): proves JUnit 5 + Mockito + AssertJ work.
 * <p>
 * Unit test conventions: no Spring context, no middleware; instantiate the class under test directly and use Mockito doubles for external dependencies.
 * Names end with Test → run by surefire in the test phase, no Docker needed.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private ConstraintViolation<Object> violation;

    @Test
    @DisplayName("业务异常：错误码和提示原样返回给调用方")
    void businessException_keepsCodeAndMessage() {
        BaseResponse<?> resp = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.PARAMS_ERROR, "SKU 不存在"));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(resp.getMessage()).isEqualTo("SKU 不存在");
    }

    @Test
    @DisplayName("未知运行时异常：只返回通用提示，不把内部异常信息暴露给前端")
    void runtimeException_hidesInternalDetail() {
        BaseResponse<?> resp = handler.runtimeExceptionHandler(
                new IllegalStateException("Connection refused: mysql:3306"));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(resp.getMessage()).doesNotContain("mysql");
    }

    @Test
    @DisplayName("参数校验异常：取校验器给出的提示（ConstraintViolation 用 Mockito 替身）")
    void constraintViolation_usesViolationMessage() {
        // ConstraintViolation is an interface whose real instances come from a Validator; in a unit test a mock sets the return value directly
        when(violation.getMessage()).thenReturn("数量必须大于 0");

        BaseResponse<?> resp = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(resp.getMessage()).isEqualTo("数量必须大于 0");
    }

    @Test
    @DisplayName("C2 被限流：HTTP 429 + Retry-After + 错误码 42900，而不是兜底的 500")
    void tooManyRequests_429() {
        var resp = handler.tooManyRequestsHandler(new TooManyRequestsException("商家 7 下单过于频繁"));

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS.getCode());
    }
}
