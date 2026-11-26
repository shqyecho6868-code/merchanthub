package com.merchanthub.user.account.model.dto;

/** Login / refresh response: identity fields are for client display; what actually counts is the signed copy inside the token */
public record TokenVO(long userId, String role, Long tenantId, String accessToken, String refreshToken) {
}
