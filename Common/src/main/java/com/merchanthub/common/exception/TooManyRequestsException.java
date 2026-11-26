package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** Rate limited (HTTP 429, C2). Uses a real status code like 403 so the gateway, monitoring and clients can tell "rate limited" from "business failure" at a glance */
public class TooManyRequestsException extends BusinessException {

    public TooManyRequestsException(String message) {
        super(ErrorCode.TOO_MANY_REQUESTS, message);
    }
}
