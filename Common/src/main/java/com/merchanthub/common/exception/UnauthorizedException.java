package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** Missing or invalid identity (HTTP 401) */
public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String message) {
        super(ErrorCode.NOT_LOGIN_ERROR, message);
    }
}
