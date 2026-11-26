package com.merchanthub.common.model.dto.validation;

import java.io.Serial;
import java.io.Serializable;

import com.merchanthub.common.constant.UserStateConstant;

import lombok.Data;

/**
 * User status response
 *
 * Returned by the internal validation API with the user's status
 * Shared by UserService and other services
 */
@Data
public class UserStatusResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * User ID
     */
    private Long userId;

    /**
     * User status
     * @see UserStateConstant
     * 0: normal
     * 1: banned
     * 2: cancelled
     * null: user does not exist
     */
    private Integer state;

    /**
     * Status description
     */
    private String stateName;

}