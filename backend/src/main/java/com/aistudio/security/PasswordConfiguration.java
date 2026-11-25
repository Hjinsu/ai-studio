package com.aistudio.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 인코더. 웹·CLI 실행 모두에서 사용한다(ADR-031). 강도 허용 범위 12~14.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {

    @Bean
    BCryptPasswordEncoder passwordEncoder(@Value("${ai-studio.security.bcrypt-strength:12}") int strength) {
        if (strength < 12 || strength > 14) {
            throw new IllegalStateException("ai-studio.security.bcrypt-strength는 12~14여야 합니다: " + strength);
        }
        return new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, strength);
    }
}
