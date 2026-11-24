package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** ADR-044: 쿠키 환경별 설정(local/docker Secure=false, prod Secure=true), 유휴 30분. */
class SessionCookieConfigTest {

    private static Properties load(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(file));
        return factory.getObject();
    }

    @Test
    void baseConfigurationDefinesNamedHttpOnlyLaxCookie() {
        Properties base = load("application.yml");
        assertThat(base.getProperty("server.servlet.session.cookie.name")).isEqualTo("AI_STUDIO_SESSION");
        assertThat(base.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(base.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(base.getProperty("server.servlet.session.cookie.path")).isEqualTo("/");
        assertThat(base.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
        assertThat(base.getProperty("server.servlet.session.timeout")).isEqualTo("30m");
        assertThat(base.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    @Test
    void productionOverridesSecureFlag() {
        assertThat(load("application-prod.yml").getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(load("application-local.yml").getProperty("server.servlet.session.cookie.secure")).isNull();
        assertThat(load("application-docker.yml").getProperty("server.servlet.session.cookie.secure")).isNull();
    }
}
