package com.merchanthub.common.exception;

import com.merchanthub.common.common.ErrorCode;

/** 越权：已登录但角色不允许（HTTP 403）。和 401 的区别：401 是"你是谁我不知道"，403 是"知道你是谁，但你不能做" */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN_ERROR, message);
    }
}
