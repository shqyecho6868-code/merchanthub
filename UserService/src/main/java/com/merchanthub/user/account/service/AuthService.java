package com.merchanthub.user.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.exception.UnauthorizedException;
import com.merchanthub.common.security.JwtCodec;
import com.merchanthub.common.security.JwtProperties;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.user.account.mapper.AccountMapper;
import com.merchanthub.user.account.mapper.TenantMapper;
import com.merchanthub.user.account.model.dto.TokenVO;
import com.merchanthub.user.account.model.entity.Account;
import com.merchanthub.user.account.model.entity.Tenant;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Register / login / refresh (rewritten in D1, replacing the old IM's SMS code + MD5 login).
 * <p>
 * Issued tokens are also written to Redis (key = access:token:{userId}): besides verifying the signature, the gateway checks "is this the current one in Redis",
 * so re-login (kicking out the old token) and deleting the key on a ban both invalidate the old token immediately -- pure JWT can't revoke actively.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Do a BCrypt comparison even when the username doesn't exist, so "no such user" and "wrong password" take the same time and usernames can't be enumerated by response time */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("dummy-password-for-timing");

    private final AccountMapper accountMapper;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtCodec jwt;
    private final JwtProperties jwtProps;
    private final StringRedisTemplate redis;

    /** Buyer self-registration. Merchant accounts can only be opened via onboarding approval (OnboardingDelegate) */
    public long registerBuyer(String username, String password) {
        Account a = new Account();
        a.setUsername(username);
        a.setPasswordHash(passwordEncoder.encode(password));
        a.setRole(Role.BUYER.name());
        a.setStatus(Account.ENABLED);
        try {
            accountMapper.insert(a);
        } catch (DuplicateKeyException e) {
            // Don't check-then-insert: when two requests register the same name concurrently, both "checks" find nothing; the unique index is the final arbiter
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        log.info("Buyer registered userId={} username={}", a.getId(), username);
        return a.getId();
    }

    public TokenVO login(String username, String password) {
        Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
        if (a == null) {
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new BusinessException(ErrorCode.LOGIN_ERROR);
        }
        // Unknown user / wrong password / disabled all return the same error: don't reveal that "this username exists"
        if (!passwordEncoder.matches(password, a.getPasswordHash()) || !isUsable(a)) {
            throw new BusinessException(ErrorCode.LOGIN_ERROR);
        }
        log.info("Login succeeded userId={} role={} tenantId={}", a.getId(), a.getRole(), a.getTenantId());
        return issue(a);
    }

    /** Exchange a Refresh-Token for a new token pair. Identity is re-read from the DB: if role / status changed, the new token reflects it immediately */
    public TokenVO refresh(String refreshToken) {
        Claims claims = jwt.parse(refreshToken);
        if (claims == null || claims.getSubject() == null) {
            throw new UnauthorizedException("Refresh-Token is invalid or expired");
        }
        String stored = redis.opsForValue().get(CommonConstant.REFRESH_TOKEN_PREFIX + claims.getSubject());
        if (!refreshToken.equals(stored)) {
            throw new UnauthorizedException("Refresh-Token has been invalidated");
        }
        Account a = accountMapper.selectById(Long.parseLong(claims.getSubject()));
        if (a == null || !isUsable(a)) {
            throw new UnauthorizedException("Account unavailable");
        }
        return issue(a);
    }

    /** Account must be enabled; merchant accounts also require the merchant to be active (if the merchant is suspended, its staff can't log in either) */
    private boolean isUsable(Account a) {
        if (a.getStatus() == null || a.getStatus() != Account.ENABLED) {
            return false;
        }
        Role role = Role.parse(a.getRole());
        if (role == null || !role.isUserRole()) {
            return false;
        }
        if (role.isMerchant()) {
            Tenant t = a.getTenantId() == null ? null : tenantMapper.selectById(a.getTenantId());
            return t != null && t.getStatus() != null && t.getStatus() == Tenant.OPEN;
        }
        return true;
    }

    private TokenVO issue(Account a) {
        Role role = Role.parse(a.getRole());
        Long tenantId = role.isMerchant() ? a.getTenantId() : null;
        String access = jwt.issue(a.getId(), tenantId, role.name(), jwtProps.getAccessTtl());
        String refresh = jwt.issue(a.getId(), tenantId, role.name(), jwtProps.getRefreshTtl());
        // Overwrite: when the same account logs in again, the old token no longer passes the gateway (single sign-on)
        redis.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + a.getId(), access, jwtProps.getAccessTtl());
        redis.opsForValue().set(CommonConstant.REFRESH_TOKEN_PREFIX + a.getId(), refresh, jwtProps.getRefreshTtl());
        return new TokenVO(a.getId(), role.name(), tenantId, access, refresh);
    }
}
