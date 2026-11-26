package com.merchanthub.common.model.dto.validation;

import java.io.Serial;
import java.io.Serializable;

import com.merchanthub.common.enums.ValidationError;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message send permission check response
 *
 * Carries the result of the message send permission check
 * Shared by UserService and RealTimeService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageValidateResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Whether sending is allowed
     */
    private boolean allowed;

    /**
     * Reject reason (optional)
     * e.g. NOT_FRIEND, BLOCKED_BY_RECEIVER, FRIEND_DELETED
     * Obtained via ValidationError.name()
     * @see ValidationError
     */
    private String rejectReason;

    /**
     * Error code (optional)
     * Range 91001-91008
     * Obtained via ValidationError.getCode()
     * @see ValidationError
     */
    private Integer errorCode;

    /**
     * Create an "allowed" response
     */
    public static MessageValidateResponse allow() {
        return MessageValidateResponse.builder()
                .allowed(true)
                .build();
    }

    /**
     * Create a "rejected" response
     *
     * @param errorCode    error code (use ValidationError.getCode())
     * @param rejectReason reject reason (use ValidationError.name())
     * @return the rejected response
     */
    public static MessageValidateResponse reject(Integer errorCode, String rejectReason) {
        return MessageValidateResponse.builder()
                .allowed(false)
                .errorCode(errorCode)
                .rejectReason(rejectReason)
                .build();
    }
}