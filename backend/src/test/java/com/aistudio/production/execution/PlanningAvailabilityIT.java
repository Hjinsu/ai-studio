package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.stage.PlanningReview;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;

@Tag("integration")
@SpringBootTest(properties = {"ai-studio.production.planning-enabled=true",
        "ai-studio.production.executor.enabled=true", "ai-studio.production.executor.auto-start=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlanningAvailabilityIT.MissingProvider.class)
class PlanningAvailabilityIT {
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @TestConfiguration
    static class MissingProvider {
        @Bean
        static BeanDefinitionRegistryPostProcessor removePlanningProvider() {
            return registry -> registry.removeBeanDefinition("planningProvider");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionExecutor executor;
    @Autowired PlanningExecutionPort planning;
    @Autowired PlanningReview review;

    @Test
    void missingProviderKeepsExistingPlanningQueuedAndRoutesNewRequestsToDiscovery() throws Exception {
        assertThat(planning).isNotNull();
        assertThat(review).isNotNull();
        var admin = ApiTestSupport.admin(accounts, jdbc);
        var actor = Actor.admin(admin.id());
        long channel = channels.create("missing-planning-provider", "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", BigDecimal.TEN, "UTC", null, actor);

        long existing = create(admin, channel, "existing-planning");
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-planning-1' WHERE id = ?", existing);
        assertThat(executor.runOnce("missing-planning-provider")).isFalse();
        assertThat(jdbc.queryForMap("SELECT pipeline_version, status, lease_epoch FROM production_request WHERE id = ?",
                existing)).containsEntry("pipeline_version", "v2-planning-1")
                .containsEntry("status", "QUEUED").containsEntry("lease_epoch", 0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ?",
                Integer.class, existing)).isZero();

        long fallback = create(admin, channel, "new-discovery");
        assertThat(jdbc.queryForObject("SELECT pipeline_version FROM production_request WHERE id = ?", String.class,
                fallback)).isEqualTo("v2-discovery-1");
        assertThat(executor.runOnce("discovery-without-planning")).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, existing))
                .isZero();
    }

    private long create(AdminPrincipal admin, long channel, String key) throws Exception {
        mvc.perform(post("/api/v1/production-requests").with(ApiTestSupport.as(admin))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"%d\",\"contentType\":\"SHORTS\"}".formatted(channel)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
    }
}
