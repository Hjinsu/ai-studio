package com.aistudio;

import java.time.ZoneOffset;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class AiStudioApplication {

    public static void main(String[] args) {
        // 서버 내부 시간은 UTC로 고정한다 (AGENTS: UTC 저장)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        if (isAdminCommand(args)) {
            ConfigurableApplicationContext context = adminCli().run(args);
            System.exit(SpringApplication.exit(context));
        }
        SpringApplication.run(AiStudioApplication.class, args);
    }

    static boolean isAdminCommand(String[] args) {
        return args.length > 0 && "admin".equals(args[0]);
    }

    /** 관리자 CLI 애플리케이션: 웹 서버 없이(WebApplicationType.NONE) 실행 후 종료 코드 반환 (ADR-031). */
    public static SpringApplicationBuilder adminCli() {
        return new SpringApplicationBuilder(AiStudioApplication.class)
                .web(WebApplicationType.NONE)
                .properties("ai-studio.cli.admin=true", "spring.main.banner-mode=off");
    }
}
