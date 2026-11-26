package com.aistudio.production.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.channel.ChannelService;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.DiscoveryProvider;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false"
})
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
    @Autowired BudgetControlService control;
    @Autowired DiscoveryExecutionPort discovery;
    @MockitoBean DiscoveryProvider provider;
    @Autowired LockRetryingTransactions transactions;
    @Autowired JsonMapper json;

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

        mvc.perform(get(PATH).param("page", "0").param("size", "1").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(location.substring(PATH.length() + 1)))
                .andExpect(jsonPath("$.items[0].channelId").value(Long.toString(channelId)))
                .andExpect(jsonPath("$.items[0].selectedTitle").isEmpty())
                .andExpect(jsonPath("$.items[0].reservedKrw").isString())
                .andExpect(jsonPath("$.items[0].createdAt").isString());
        mvc.perform(get(PATH).param("page", "-1").with(ApiTestSupport.as(admin)))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE production_step SET last_error_code = 'DISCOVERY_NO_ELIGIBLE_CANDIDATE'"
                + " WHERE production_request_id = ? AND stage = 'DISCOVER'",
                Long.parseLong(location.substring(PATH.length() + 1)));
        mvc.perform(get(location).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.steps[0].lastErrorCode").value("DISCOVERY_NO_ELIGIBLE_CANDIDATE"));

        mvc.perform(post(location + "/cancel").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequestedAt").isString());
        mvc.perform(post(location + "/cancel").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequestedAt").isString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_request WHERE request_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void replaysLegacyDefaultsWithoutBackfillingDiscovery() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        long channel = channel(admin.id());
        String key = ApiTestSupport.uniqueKey("legacy");
        jdbc.update("""
                INSERT INTO production_request (channel_id, channel_config_id, requested_by, content_type,
                    pipeline_version, request_key, request_hash, limit_krw, created_at, status)
                SELECT ?, id, ?, 'SHORTS', 'v2-fixed-1', ?, ?, 100, UTC_TIMESTAMP(6), 'CANCELLED'
                FROM channel_config WHERE channel_id = ?
                """, channel, admin.id(), key,
                CanonicalJson.sha256Hex(admin.id() + ":" + channel + ":SHORTS"), channel);
        String body = "{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\","
                + "\"selectionMode\":\"AUTO\",\"candidateCount\":8}";
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
        mvc.perform(get(PATH + "/" + id + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_discovery WHERE production_request_id = ?",
                Long.class, id)).isZero();
    }

    @Test
    void persistsDiscoverySnapshotAndVersionedManualChoice() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channel(admin.id());
        String key = ApiTestSupport.uniqueKey("manual");
        String body = "{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\","
                + "\"selectionMode\":\"MANUAL\",\"candidateCount\":5}";
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(":5", ":4")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(":5", ":6")))
                .andExpect(status().isConflict());
        long id = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
        String detail = PATH + "/" + id;
        mvc.perform(get(detail + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidateCount").value(5))
                .andExpect(jsonPath("$.selectionMode").value("MANUAL"))
                .andExpect(jsonPath("$.awaitingSelection").value(false));
        assertThat(jdbc.queryForObject("SELECT language FROM production_discovery WHERE production_request_id = ?",
                String.class, id)).isEqualTo("en");
        assertThat(jdbc.queryForObject("SELECT pipeline_version FROM production_request WHERE id = ?",
                String.class, id)).isEqualTo("v2-discovery-1");

        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated discovery fixture", control.get().version(), actor);
        jdbc.update("""
                UPDATE production_request SET status = 'RUNNING', lease_owner = 'fixture', lease_epoch = 1,
                    lease_until = UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE WHERE id = ?
                """, id);
        long step = jdbc.queryForObject("""
                SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'DISCOVER'
                """, Long.class, id);
        jdbc.update("UPDATE production_step SET status = 'RUNNING' WHERE id = ?", step);
        var scores = new Scores(25, 20, 20, 15, 15, 5);
        var candidate = new Candidate("Gravity", "How can gravity change paths?", "Gravity and paths",
                "Orbital diagram", List.of("Hook", "Comparison"),
                List.of(new EvidencePlan("TEXTBOOK", "orbital examples")), List.of(), scores, "Clear outline");
        var assessments = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(position -> new EvaluatedCandidate(position, candidate, scores, 100,
                        position == 1 || position == 3 ? List.of() : List.of(Block.DUPLICATE),
                        List.of(), "Fixture evaluation")).toList();
        var input = discovery.input(id);
        org.mockito.Mockito.when(provider.generate(input)).thenReturn(new Response(1, java.util.Collections.nCopies(5, candidate)));
        var owner = new StepOwner(id, step, "fixture", 1);
        var receipt = discovery.discover(owner, input).receipt();
        transactions.run(() -> {
            discovery.applyEvaluation(owner, receipt, assessments);
            jdbc.update("UPDATE production_step SET status = 'SUCCEEDED' WHERE id = ?", step);
            jdbc.update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
        });
        String batchJson = mvc.perform(get(detail + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.awaitingSelection").value(true))
                .andExpect(ApiTestSupport.matchesContract()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(batchJson).path("batchToken").asString();
        String foreignToken = CanonicalJson.sha256(List.of("discovery-batch", id + 1, 1));
        mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(choice(1, 1, foreignToken))).andExpect(status().isConflict());
        mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(choice(2, 1, token))).andExpect(status().is(422));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                            .content(choice(1, 1, token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.chosenCandidateId").value(1))
                    .andExpect(jsonPath("$.version").value(2)).andExpect(ApiTestSupport.matchesContract());
        }
        mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(choice(2, 1, token))).andExpect(status().isConflict());

        long selectStep = jdbc.queryForObject("""
                SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'SELECT'
                """, Long.class, id);
        var rechecked = assessments.stream().map(item -> item.candidateId() == 1
                ? new EvaluatedCandidate(1, candidate, scores, 100, List.of(Block.DUPLICATE), List.of(), "New match")
                : item).toList();
        transactions.run(() -> {
            jdbc.update("""
                    UPDATE production_request SET lease_owner = 'selection', lease_epoch = 2,
                        lease_until = UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE WHERE id = ?
                    """, id);
            discovery.invalidateManualChoice(new StepOwner(id, selectStep, "selection", 2), 2, rechecked);
            jdbc.update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
        });
        mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(choice(1, 1, token))).andExpect(status().isConflict());
        String recheckedJson = mvc.perform(get(detail + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.awaitingSelection").value(true))
                .andExpect(jsonPath("$.version").value(3)).andReturn().getResponse().getContentAsString();
        String newToken = json.readTree(recheckedJson).path("batchToken").asString();
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var choose = workers.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post(detail + "/selection").with(ApiTestSupport.as(admin))
                                .contentType(MediaType.APPLICATION_JSON).content(choice(3, 3, newToken)))
                        .andReturn().getResponse().getStatus();
            });
            var cancel = workers.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                mvc.perform(post(detail + "/cancel").with(ApiTestSupport.as(admin))).andExpect(status().isOk());
                return null;
            });
            gate.countDown();
            int status = choose.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);
            assertThat(status).isIn(200, 409);
            assertThat(jdbc.queryForObject("SELECT chosen_candidate_id FROM production_discovery WHERE production_request_id = ?",
                    Integer.class, id)).isEqualTo(status == 200 ? 3 : null);
        }
        mvc.perform(get(detail + "/discovery").with(ApiTestSupport.as(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingSelection").value(false));
    }

    private static String choice(int candidateId, int version, String token) {
        return "{\"candidateId\":%d,\"version\":%d,\"batchToken\":\"%s\"}".formatted(candidateId, version, token);
    }

    private long channel(long adminId) {
        Actor actor = Actor.admin(adminId);
        long channel = channels.create(ApiTestSupport.uniqueKey("discovery"), "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", new BigDecimal("100"), "UTC", null, actor);
        return channel;
    }
}
