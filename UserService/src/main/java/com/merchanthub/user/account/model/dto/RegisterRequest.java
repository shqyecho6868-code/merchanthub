package com.merchanthub.user.account.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 买家注册（商家账号不能自己注册：只能经入驻审批开通） */
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "登录名为 4~32 位字母、数字或下划线") String username,
        @NotBlank @Size(min = 8, max = 64, message = "密码 8~64 位") String password) {
}
