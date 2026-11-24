package com.aistudio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DB 없이 Actuator 노출 정책(ADR-008)을 검증한다. DB 연결 health는 DatabaseBootstrapIT에서 검증한다.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndHidesDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/metrics",
            "/actuator/configprops", "/actuator/loggers", "/actuator/heapdump"})
    void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // health 하위 구성요소 경로는 Actuator가 직접 빈 본문 404로 응답한다 (show-components: never)
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/db", "/actuator/health/diskSpace", "/actuator/health/liveness",
            "/actuator/health/readiness"})
    void healthComponentsAreNotExposed(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound());
    }
}
