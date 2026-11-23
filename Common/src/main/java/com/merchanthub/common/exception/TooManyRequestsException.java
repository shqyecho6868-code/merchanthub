package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** 被限流（HTTP 429，C2）。和 403 一样用真实状态码：网关、监控、客户端都要能一眼区分"被限流"和"业务失败" */
public class TooManyRequestsException extends BusinessException {

    public TooManyRequestsException(String message) {
        super(ErrorCode.TOO_MANY_REQUESTS, message);
    }
}
