package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Onboarding application / revise and resubmit after rejection */
public record ApplicationRequest(
        @NotBlank @Size(max = 64) String shopName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "登录名为 4~32 位字母、数字或下划线") String adminUsername,
        @NotBlank @Size(min = 8, max = 64, message = "密码 8~64 位") String adminPassword) {
}
