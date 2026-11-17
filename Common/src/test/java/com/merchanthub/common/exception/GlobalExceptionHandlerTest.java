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
 * 单元测试示例（A2+）：证明 JUnit 5 + Mockito + AssertJ 能跑。
 * <p>
 * 单元测试约定：不起 Spring 容器、不连中间件，直接 new 被测对象；外部依赖用 Mockito 替身。
 * 命名以 Test 结尾 → 由 surefire 在 test 阶段执行，无需 Docker。
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
        // ConstraintViolation 是接口，真实对象要靠 Validator 生成；单测里用 mock 直接指定返回值
        when(violation.getMessage()).thenReturn("数量必须大于 0");

        BaseResponse<?> resp = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(resp.getMessage()).isEqualTo("数量必须大于 0");
    }
}
