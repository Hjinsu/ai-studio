package com.aistudio.production.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.channel.ChannelService;
import com.aistudio.security.AdminAccountService;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionRequestApiIT {
    private static final String PATH = "/api/v1/production-requests";
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsBeforeSelectionAndReplaysOnlyIdenticalRequests() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        long channelId = channels.create("production-" + UUID.randomUUID(), "en", "UTC", null,
                Actor.admin(admin.id())).channel().id();
        policies.create("CONTENT_TYPE", channelId, "SHORTS", "LIFETIME", new BigDecimal("2000"), "UTC",
                "isolated production test", Actor.admin(admin.id()));
        String key = ApiTestSupport.uniqueKey("production");
        String body = "{\"channelId\":\"" + channelId + "\",\"contentType\":\"SHORTS\"}";

        var result = mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(header().exists("Location"))
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith(PATH + "/");

        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("SHORTS", "LONGFORM")))
                .andExpect(status().isConflict());

        mvc.perform(get(location).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentId").doesNotExist())
                .andExpect(jsonPath("$.runId").doesNotExist())
                .andExpect(jsonPath("$.steps.length()").value(11))
                .andExpect(jsonPath("$.steps[0].stage").value("DISCOVER"))
                .andExpect(jsonPath("$.steps[10].stage").value("QC"))
                .andExpect(jsonPath("$.reservedKrw").isString())
                .andExpect(jsonPath("$.recognizedKrw").isString());

        mvc.perform(post(location + "/cancel").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequestedAt").isString());
        mvc.perform(post(location + "/cancel").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequestedAt").isString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_request WHERE request_key = ?",
                Integer.class, key)).isEqualTo(1);
    }
}
