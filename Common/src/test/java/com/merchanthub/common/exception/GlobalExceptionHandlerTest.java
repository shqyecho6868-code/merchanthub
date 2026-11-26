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
    @DisplayName("Business exception: error code and message are returned to the caller as-is")
    void businessException_keepsCodeAndMessage() {
        BaseResponse<?> resp = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.PARAMS_ERROR, "SKU not found"));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(resp.getMessage()).isEqualTo("SKU not found");
    }

    @Test
    @DisplayName("Unknown runtime exception: return only a generic message, never expose internal details to the client")
    void runtimeException_hidesInternalDetail() {
        BaseResponse<?> resp = handler.runtimeExceptionHandler(
                new IllegalStateException("Connection refused: mysql:3306"));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
        assertThat(resp.getMessage()).doesNotContain("mysql");
    }

    @Test
    @DisplayName("Validation exception: use the validator's message (ConstraintViolation is a Mockito double)")
    void constraintViolation_usesViolationMessage() {
        // ConstraintViolation is an interface whose real instances come from a Validator; in a unit test a mock sets the return value directly
        when(violation.getMessage()).thenReturn("Quantity must be greater than 0");

        BaseResponse<?> resp = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));

        assertThat(resp.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        assertThat(resp.getMessage()).isEqualTo("Quantity must be greater than 0");
    }

    @Test
    @DisplayName("C2 rate limited: HTTP 429 + Retry-After + error code 42900, not the catch-all 500")
    void tooManyRequests_429() {
        var resp = handler.tooManyRequestsHandler(new TooManyRequestsException("Merchant 7 is placing orders too frequently"));

        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS.getCode());
    }
}
