package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** Access denied: authenticated but the role is not allowed (HTTP 403). Unlike 401 ("I don't know who you are"), 403 means "I know who you are, but you can't do this" */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN_ERROR, message);
    }
}
