package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Onboarding application / revise and resubmit after rejection */
public record ApplicationRequest(
        @NotBlank @Size(max = 64) String shopName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{4,32}", message = "Username must be 4-32 letters, digits or underscores") String adminUsername,
        @NotBlank @Size(min = 8, max = 64, message = "Password must be 8-64 characters") String adminPassword) {
}
