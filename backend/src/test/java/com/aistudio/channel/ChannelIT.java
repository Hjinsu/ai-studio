package com.aistudio.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
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
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 채널·설정 revision (ADR-034, 039, 042, 059) + OpenAPI 계약. */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminPrincipal admin;

    @BeforeEach
    void setUp() {
        admin = ApiTestSupport.admin(accounts, jdbc);
    }

    private JsonNode createChannel(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/channels").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"language\":\"en\",\"timezone\":\"America/New_York\"}"))
                .andExpect(status().isCreated())
                .andExpect(ApiTestSupport.matchesContract())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static String settings(String extra) {
        return "{\"defaultContentType\":\"LONGFORM\",\"shortsMinSeconds\":40,\"shortsMaxSeconds\":60,"
                + "\"longformMinSeconds\":480,\"longformMaxSeconds\":720,\"contentStrategy\":\"Explain physics\","
                + "\"targetAudience\":\"Students\",\"narrationStyle\":\"Calm\",\"visualStyle\":\"Diagrams\","
                + "\"syntheticDisclosureDefault\":true" + extra + "}";
    }

    @Test
    void createListGetAndUpdateChannelWithContract() throws Exception {
        JsonNode created = createChannel(ApiTestSupport.uniqueKey("science"));
        String id = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("currentConfigRevisionNo").asInt()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/channels?status=ACTIVE&size=5").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/channels").with(ApiTestSupport.as(admin)))
                .andExpect(jsonPath("$.size").value(20));
        mockMvc.perform(get("/api/v1/channels?size=101").with(ApiTestSupport.as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch("/api/v1/channels/" + id).with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"timezone\":\"Asia/Seoul\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(ApiTestSupport.matchesContract());
        // 오래된 version → 409
        mockMvc.perform(patch("/api/v1/channels/" + id).with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(patch("/api/v1/channels/" + id).with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"timezone\":\"Mars/Base\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void duplicateNameIsDuplicateResource() throws Exception {
        String name = ApiTestSupport.uniqueKey("dup");
        createChannel(name);
        mockMvc.perform(post("/api/v1/channels").with(ApiTestSupport.as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"language\":\"en\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void statusTransitionsAndArchivedIsTerminal() throws Exception {
        String id = createChannel(ApiTestSupport.uniqueKey("lifecycle")).get("id").asString();
        mockMvc.perform(post("/api/v1/channels/" + id + "/resume").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mockMvc.perform(post("/api/v1/channels/" + id + "/pause").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(post("/api/v1/channels/" + id + "/archive").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
        // 보관 후 복원·수정·설정 변경 금지, 조회는 가능(데이터 보존)
        mockMvc.perform(post("/api/v1/channels/" + id + "/resume").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mockMvc.perform(patch("/api/v1/channels/" + id).with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"name\":\"renamed\"}"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mockMvc.perform(post("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevisionNo\":1,\"settings\":" + settings("") + "}"))
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        mockMvc.perform(get("/api/v1/channels/" + id).with(ApiTestSupport.as(admin))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channel WHERE id = ?", Integer.class, Long.parseLong(id)))
                .isEqualTo(1);
    }

    @Test
    void configRevisionsAreImmutableHistoryWithOptimisticBase() throws Exception {
        String id = createChannel(ApiTestSupport.uniqueKey("config")).get("id").asString();
        mockMvc.perform(post("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevisionNo\":1,\"settings\":" + settings(",\"providerOptions\":{\"imageModel\":"
                                + "\"draft\"}") + ",\"changeNote\":\"strategy\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.contentStrategy").value("Explain physics"))
                .andExpect(jsonPath("$.providerOptions.imageModel").value("draft"))
                .andExpect(ApiTestSupport.matchesContract());
        // 같은 base로 다시 → 409
        mockMvc.perform(post("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevisionNo\":1,\"settings\":" + settings("") + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        mockMvc.perform(get("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin)))
                .andExpect(jsonPath("$.items[0].revisionNo").value(2))
                .andExpect(jsonPath("$.items[1].revisionNo").value(1))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/channels/" + id + "/config/revisions/1").with(ApiTestSupport.as(admin)))
                .andExpect(jsonPath("$.defaultContentType").value("SHORTS"))
                .andExpect(ApiTestSupport.matchesContract());
        mockMvc.perform(get("/api/v1/channels/" + id + "/config").with(ApiTestSupport.as(admin)))
                .andExpect(jsonPath("$.revisionNo").value(2));
        // 채널 version과 설정 revision 번호는 별개(ADR-034)
        mockMvc.perform(get("/api/v1/channels/" + id).with(ApiTestSupport.as(admin)))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.currentConfigRevisionNo").value(2));
    }

    @Test
    void providerOptionsRejectSecretLikeKeysAndInvalidRanges() throws Exception {
        String id = createChannel(ApiTestSupport.uniqueKey("secret")).get("id").asString();
        mockMvc.perform(post("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevisionNo\":1,\"settings\":" + settings(",\"providerOptions\":{\"nested\":"
                                + "{\"apiKey\":\"x\"}}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("providerOptions.nested.apiKey"));
        mockMvc.perform(post("/api/v1/channels/" + id + "/config/revisions").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseRevisionNo\":1,\"settings\":" + settings("").replace("\"shortsMinSeconds\":40",
                                "\"shortsMinSeconds\":90") + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void concurrentConfigRevisionsWithSameBaseAllowOnlyOne(@Autowired ChannelService service) throws Exception {
        long id = Long.parseLong(createChannel(ApiTestSupport.uniqueKey("race")).get("id").asString());
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                start.await();
                try {
                    service.createConfigRevision(id, 1, ChannelSettings.defaults(), "race",
                            com.aistudio.audit.AuditService.Actor.admin(admin.id()));
                    return "ok";
                } catch (com.aistudio.shared.api.ApiException e) {
                    return e.errorCode().name();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        executor.shutdown();
        assertThat(outcomes).filteredOn("ok"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("ok")).containsOnly("RESOURCE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT MAX(revision_no) FROM channel_config WHERE channel_id = ?",
                Integer.class, id)).isEqualTo(2);
    }
}
