package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** 没有身份或身份信息不合法（HTTP 401） */
public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String message) {
        super(ErrorCode.NOT_LOGIN_ERROR, message);
    }
}
