package com.aistudio.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.channel.ChannelService;
import com.aistudio.production.execution.ProductionExecutor;
import com.aistudio.security.AdminAccountService;

@Tag("integration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false"
})
class CombinedDiscoveryIT {
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired ProductionExecutor executor;

    @Test
    void autoUsesRealPortsAndListsTheLinkedSelection() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channels.create("Combined discovery", "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", BigDecimal.TEN, "UTC", null, actor);
        policies.create("SYSTEM", null, null, "DAY", BigDecimal.valueOf(100), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", BigDecimal.valueOf(100), "UTC", null, actor);
        control.setSpending(true, "isolated fake", control.get().version(), actor);

        String path = "/api/v1/production-requests";
        String location = mvc.perform(post(path).with(ApiTestSupport.as(admin))
                        .header("Idempotency-Key", ApiTestSupport.uniqueKey("combined"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"%d\",\"contentType\":\"SHORTS\"}".formatted(channel)))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        assertThat(executor.runOnce("combined-auto")).isTrue();
        mvc.perform(get(location).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.contentId").isString());
        mvc.perform(get(location + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(8))
                .andExpect(jsonPath("$.selectedCandidateId").isNumber());
        mvc.perform(get(path).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.items[0].selectedTitle").isNotEmpty())
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_operation WHERE status = 'SETTLED'", Integer.class))
                .isEqualTo(8);
    }
}
