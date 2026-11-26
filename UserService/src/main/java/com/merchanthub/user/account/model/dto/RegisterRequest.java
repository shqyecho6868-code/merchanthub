package com.merchanthub.user.account.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Buyer registration (merchant accounts can't self-register: they can only be opened through onboarding approval) */
public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "登录名为 4~32 位字母、数字或下划线") String username,
        @NotBlank @Size(min = 8, max = 64, message = "密码 8~64 位") String password) {
}
