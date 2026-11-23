package com.merchanthub.user.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("deploy/d1/seed.sql 里 htpasswd 生成的 $2y$ 哈希 Spring 也能校验（种子账号能登录）")
    void htpasswd2yHashAccepted() {
        assertThat(encoder.matches("password123", "$2y$10$eVlhpIBFnecCZTkM6GQxjuQrNRVAd8QbVNtAgdynJP1GIKalquoh2")).isTrue();
    }

    @Test
    @DisplayName("同一个密码每次哈希都不同（随机盐），但都能校验通过")
    void randomSalt() {
        String a = encoder.encode("password123");
        String b = encoder.encode("password123");
        assertThat(a).isNotEqualTo(b);
        assertThat(encoder.matches("password123", a)).isTrue();
        assertThat(encoder.matches("password123", b)).isTrue();
    }
}
