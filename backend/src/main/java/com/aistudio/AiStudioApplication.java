package com.aistudio;

import java.time.ZoneOffset;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiStudioApplication {

    public static void main(String[] args) {
        // 서버 내부 시간은 UTC로 고정한다 (AGENTS: UTC 저장)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(AiStudioApplication.class, args);
    }
}
