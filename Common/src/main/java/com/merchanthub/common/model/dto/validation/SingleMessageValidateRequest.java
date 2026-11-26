package com.merchanthub.common.model.dto.validation;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;

/**
 * Send permission check request for a one-to-one message
 *
 * Verifies the friend relationship before a one-to-one message is sent
 * Shared by UserService and RealTimeService
 */
@Data
public class SingleMessageValidateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Sender ID
     */
    private Long senderId;

    /**
     * Receiver ID
     */
    private Long receiverId;

    /**
     * Session ID
     */
    private Long sessionId;

    /**
     * Create a validation request
     *
     * @param senderId   sender ID
     * @param receiverId receiver ID
     * @param sessionId  session ID
     * @return the validation request
     */
    public static SingleMessageValidateRequest of(Long senderId, Long receiverId, Long sessionId) {
        SingleMessageValidateRequest request = new SingleMessageValidateRequest();
        request.setSenderId(senderId);
        request.setReceiverId(receiverId);
        request.setSessionId(sessionId);
        return request;
    }
}