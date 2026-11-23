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
 * 注册 / 登录 / 刷新（D1 重写，替换旧 IM 的验证码 + MD5 登录）。
 * <p>
 * token 签发后同时写进 Redis（key = access:token:{userId}）：网关除了验签，还核对"是不是 Redis 里当前那一个"，
 * 这样重新登录（挤掉旧 token）、封号后删 key 都能让旧 token 立即失效 —— 纯 JWT 做不到主动失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 用户名不存在时也做一次 BCrypt 比较，让"用户不存在"和"密码错"耗时相同，不能靠响应时间枚举登录名 */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("dummy-password-for-timing");

    private final AccountMapper accountMapper;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtCodec jwt;
    private final JwtProperties jwtProps;
    private final StringRedisTemplate redis;

    /** 买家自助注册。商家账号只能通过入驻审批开通（OnboardingDelegate） */
    public long registerBuyer(String username, String password) {
        Account a = new Account();
        a.setUsername(username);
        a.setPasswordHash(passwordEncoder.encode(password));
        a.setRole(Role.BUYER.name());
        a.setStatus(Account.ENABLED);
        try {
            accountMapper.insert(a);
        } catch (DuplicateKeyException e) {
            // 不先查再插：两个请求同时注册同名时"先查"都查不到，唯一索引才是最终裁决
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        log.info("买家注册 userId={} username={}", a.getId(), username);
        return a.getId();
    }

    public TokenVO login(String username, String password) {
        Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
        if (a == null) {
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new BusinessException(ErrorCode.LOGIN_ERROR);
        }
        // 用户不存在 / 密码错 / 被禁用返回同一个错误：不告诉外部"这个登录名存在"
        if (!passwordEncoder.matches(password, a.getPasswordHash()) || !isUsable(a)) {
            throw new BusinessException(ErrorCode.LOGIN_ERROR);
        }
        log.info("登录成功 userId={} role={} tenantId={}", a.getId(), a.getRole(), a.getTenantId());
        return issue(a);
    }

    /** 用 Refresh-Token 换一对新 token。身份从库里重新读：角色 / 状态变了，新 token 立即反映 */
    public TokenVO refresh(String refreshToken) {
        Claims claims = jwt.parse(refreshToken);
        if (claims == null || claims.getSubject() == null) {
            throw new UnauthorizedException("Refresh-Token 无效或已过期");
        }
        String stored = redis.opsForValue().get(CommonConstant.REFRESH_TOKEN_PREFIX + claims.getSubject());
        if (!refreshToken.equals(stored)) {
            throw new UnauthorizedException("Refresh-Token 已失效");
        }
        Account a = accountMapper.selectById(Long.parseLong(claims.getSubject()));
        if (a == null || !isUsable(a)) {
            throw new UnauthorizedException("账号不可用");
        }
        return issue(a);
    }

    /** 账号启用；商家账号还要求商家在营业（商家被停用，员工也不能再登录） */
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
        // 覆盖写：同一账号再次登录，旧 token 在网关那一关就过不去了（单点登录）
        redis.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + a.getId(), access, jwtProps.getAccessTtl());
        redis.opsForValue().set(CommonConstant.REFRESH_TOKEN_PREFIX + a.getId(), refresh, jwtProps.getRefreshTtl());
        return new TokenVO(a.getId(), role.name(), tenantId, access, refresh);
    }
}
