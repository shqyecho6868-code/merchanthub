package com.merchanthub.user.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * BCrypt: every password gets its own random salt and hashing is deliberately slow (cost 10 ~ tens of ms), so even a leaked DB is hard to crack in bulk.
     * The old IM used MD5 + a fixed salt: everyone shared one salt, so one rainbow table could crack every weak password (migration-map line 49)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
