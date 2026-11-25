package com.aistudio.shared.time;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UTC 시계. DATETIME(6) 정밀도와 맞추기 위해 마이크로초 단위로 끊는다(저장 후 비교 불일치 방지).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
