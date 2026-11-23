package com.merchanthub.user.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * BCrypt：每个密码自带随机盐、计算故意慢（cost 10 ≈ 几十毫秒），库被拖走也很难批量反推。
     * 旧 IM 用 MD5 + 固定盐：所有人同一个盐，一张彩虹表就能撞出全部弱密码（migration-map 第 49 行）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
