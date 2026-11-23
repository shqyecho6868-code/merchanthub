package com.merchanthub.user.account.model.dto;

/** 登录 / 刷新的返回：身份字段方便客户端展示，真正生效的是 token 里签过名的那一份 */
public record TokenVO(long userId, String role, Long tenantId, String accessToken, String refreshToken) {
}
