package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import com.aistudio.channel.ChannelSettings;
import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.discovery.DiscoveryFixtures;
import com.aistudio.production.discovery.DiscoveryRules;
import com.aistudio.production.discovery.RuleBasedDiscoveryEvaluator;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper;
import com.aistudio.production.planning.PlanningResultMapper;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2-03 RESEARCH, SCRIPT and SCENES in the executor against an isolated MariaDB (V1~V12). Requests are created
 * through the production API with the planning pipeline enabled, DISCOVER and SELECT run through the real V2-02
 * ports, and each planning effect goes through the real V2-01 cost port via {@link ContractPlanningPort}. The FAKE
 * planning provider and the local review are the product implementations. The real planning storage/cost adapter
 * is on the safety branch; combined verification repeats these flows against it.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false",
        "ai-studio.production.executor.lease-duration=PT10S",
        "ai-studio.production.executor.recovery-recheck=PT1H",
        "ai-studio.production.planning-enabled=true" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(PlanningExecutionIT.Ports.class)
class PlanningExecutionIT {
    private static final String PATH = "/api/v1/production-requests";
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static final String NEUTRON = "How heavy would a teaspoon of neutron star be?";
    static final String TARDIGRADE = "How do tardigrades survive being completely dried out?";
    static final String VENUS = "Why is a day on Venus longer than its year?";
    static final String NO_FIXTURE = "Why are Saturn's rings so thin compared with their width?";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @TestConfiguration
    static class Ports {
        @Bean
        @Primary
        ScriptedDiscoveryProvider scriptedDiscoveryProvider() {
            return new ScriptedDiscoveryProvider();
        }

        @Bean
        ContractPlanningPort contractPlanningPort(PlanningResultMapper results, ProductionDiscoveryMapper discovery,
                ProductionCostPort cost, PlanningProvider provider, JdbcTemplate jdbc, JsonMapper json) {
            return new ContractPlanningPort(results, discovery, cost, provider, jdbc, json);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionExecutor executor;
    @Autowired ContractPlanningPort port;
    @Autowired ScriptedDiscoveryProvider discovery;
    @Autowired JsonMapper json;
    @Autowired ExecutionMapper executionMapper;
    @Autowired BudgetControlLock budgetControl;
    @Autowired LockRetryingTransactions transactions;
    @Autowired ExecutionProperties properties;
    @Autowired ProductionCostPort cost;
    @Autowired ProductionSelectionPort selection;
    @Autowired com.aistudio.production.contract.DiscoveryExecutionPort discoveryPort;
    AdminPrincipal admin;
    long channel;

    @BeforeAll
    void setUp() {
        admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated planning integration", control.get().version(), actor);
    }

    /** A fresh channel per test, so V2-02 duplicate checks of one test never affect another. */
    void newChannel() {
        Actor actor = Actor.admin(admin.id());
        ChannelSettings settings = new ChannelSettings("SHORTS", 40, 60, 480, 720, DiscoveryFixtures.STRATEGY,
                DiscoveryFixtures.AUDIENCE, "Energetic and clear", "Clean infographic", true, null);
        channel = channels.create(ApiTestSupport.uniqueKey("ifnova"), "en", "UTC", settings, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", new BigDecimal("100"), "UTC", null, actor);
    }

    @BeforeEach
    void isolate() {
        newChannel();
        port.reset();
        discovery.reset();
        jdbc.update("""
                UPDATE production_request SET status = 'FAILED', lease_owner = NULL, lease_until = NULL
                WHERE status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
                """);
    }

    // ------------------------------------------------------------------ helpers

    /** The topic to select ranks first; the fillers have no offline fixture. */
    void discover(String question) {
        Candidate target = new Candidate("Target", question, "Target: " + question,
                "Compare the effect against familiar everyday scales to make it intuitive.",
                List.of("Hook: pose the question", "Close with one open question for viewers"),
                List.of(new com.aistudio.production.contract.ProductionDiscovery.EvidencePlan("TEXTBOOK",
                        "What do current sources say about this?")),
                List.of(), new Scores(25, 20, 20, 15, 15, 5), "Fixture topic.");
        List<Candidate> list = new ArrayList<>(List.of(target));
        for (Candidate filler : DiscoveryFixtures.fillers(10)) {
            String q = filler.centralQuestion().toLowerCase(java.util.Locale.ROOT);
            if (list.size() < 5 && !q.contains("neutron") && !q.contains("venus") && !q.contains("tardigrade")
                    && !q.contains("saturn")) {
                list.add(filler);
            }
        }
        discovery.responses = input -> new Response(DiscoveryRules.RESPONSE_VERSION, list.subList(0,
                input.candidateCount()));
    }

    long create(String contentType, String extra) throws Exception {
        String key = ApiTestSupport.uniqueKey("planning");
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"contentType\":\"" + contentType + "\"" + extra
                                + "}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
    }

    /** MANUAL request: discover, then the requester chooses the fixture topic (candidate 1) through the API. */
    long shorts(String question) throws Exception {
        long id = queued(question);
        drain();
        JsonNode view = json.readTree(mvc.perform(get(PATH + "/" + id + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post(PATH + "/" + id + "/selection").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":1,\"version\":%d,\"batchToken\":\"%s\"}".formatted(
                                view.path("version").asInt(), view.path("batchToken").asString())))
                .andExpect(status().isOk());
        return id;
    }

    long queued(String question) throws Exception {
        discover(question);
        return create("SHORTS", ",\"candidateCount\":5,\"selectionMode\":\"MANUAL\"");
    }

    int drain() {
        return DiscoveryExecutionIT.drain(executor, "planning-it");
    }

    String describe(long id) {
        return jdbc.queryForList("SELECT stage, status, last_error_code, result_ref FROM production_step "
                + "WHERE production_request_id = ? ORDER BY id", id) + " reviews="
                + jdbc.queryForList("SELECT validation_json FROM production_stage_result WHERE request_id = ?",
                        String.class, id)
                + " question=" + jdbc.queryForList("SELECT JSON_VALUE(input_json, '$.question') FROM "
                        + "production_stage_result WHERE request_id = ?", String.class, id);
    }

    void assertStatus(long id, String expected) {
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo(expected);
    }

    String requestStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id);
    }

    Map<String, Object> step(long id, ProductionStage stage) {
        return jdbc.queryForMap("SELECT * FROM production_step WHERE production_request_id = ? AND stage = ?", id,
                stage.name());
    }

    int operations(long id, ProductionStage stage) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = ?
                """, Integer.class, id, stage.name());
    }

    JsonNode planning(long id) throws Exception {
        String body = mvc.perform(get(PATH + "/" + id + "/planning").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    static JsonNode result(JsonNode planning, ProductionStage stage) {
        for (JsonNode result : planning.path("results")) {
            if (result.path("stage").asString().equals(stage.name())) {
                return result;
            }
        }
        return null;
    }

    static List<String> codes(JsonNode result, String severity) {
        List<String> codes = new ArrayList<>();
        result.path("validation").path("findings").forEach(f -> {
            if (f.path("severity").asString().equals(severity)) {
                codes.add(f.path("code").asString());
            }
        });
        return codes;
    }

    void makeDue(long id) {
        jdbc.update("""
                UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE production_request_id = ? AND retry_at IS NOT NULL
                """, id);
    }

    // ------------------------------------------------------------------ success paths

    @Test
    void twoScienceTopicsRunThroughPlanningWithDifferentScriptsAndScenes() throws Exception {
        long neutron = shorts(NEUTRON);
        drain();
        long tardigrade = shorts(TARDIGRADE);
        drain();
        for (long id : List.of(neutron, tardigrade)) {
            assertStatus(id, "SUCCEEDED");
            for (ProductionStage stage : ProductionStage.values()) {
                assertThat(step(id, stage).get("status")).as(stage.name()).isEqualTo("SUCCEEDED");
            }
            assertThat(jdbc.queryForObject("SELECT pipeline_version FROM production_request WHERE id = ?",
                    String.class, id)).isEqualTo("v2-planning-1");
            for (ProductionStage stage : List.of(ProductionStage.RESEARCH, ProductionStage.SCRIPT,
                    ProductionStage.SCENES)) {
                assertThat(operations(id, stage)).isEqualTo(1);
                assertThat(step(id, stage).get("result_ref").toString()).startsWith("planning:");
            }
        }
        assertThat(port.generated(ProductionStage.RESEARCH)).isEqualTo(2);
        JsonNode first = planning(neutron);
        JsonNode second = planning(tardigrade);
        assertThat(first.path("executionMode").asString()).isEqualTo("FAKE");
        assertThat(first.path("results")).hasSize(3);
        Research research = json.treeToValue(result(first, ProductionStage.RESEARCH).path("body"), Research.class);
        assertThat(research.sources()).extracting(Source::status).allMatch(s -> s.name().equals("FIXTURE"));
        assertThat(research.claims()).extracting(Claim::kind).extracting(Enum::name)
                .contains("FACT", "HYPOTHESIS", "SCENARIO");
        Script a = json.treeToValue(result(first, ProductionStage.SCRIPT).path("body"), Script.class);
        Script b = json.treeToValue(result(second, ProductionStage.SCRIPT).path("body"), Script.class);
        assertThat(a.segments()).hasSize(7);
        assertThat(b.segments()).hasSize(6);
        assertThat(a.segments().getFirst().narration()).isNotEqualTo(b.segments().getFirst().narration());
        Scenes sa = json.treeToValue(result(first, ProductionStage.SCENES).path("body"), Scenes.class);
        Scenes sb = json.treeToValue(result(second, ProductionStage.SCENES).path("body"), Scenes.class);
        assertThat(sa.scenes()).hasSize(5);
        assertThat(sb.scenes()).hasSize(6);
        for (var pair : List.of(Map.entry(a, sa), Map.entry(b, sb))) {
            Map<String, String> text = new java.util.HashMap<>();
            pair.getKey().segments().forEach(s -> text.put(s.id(), s.narration()));
            for (Scene scene : pair.getValue().scenes()) {
                String expected = String.join(" ", scene.segmentIds().stream().map(text::get).toList());
                assertThat(scene.narration()).isEqualTo(expected);
                assertThat(scene.subtitleText()).isEqualTo(expected);
            }
            assertThat(pair.getValue().scenes().stream().mapToInt(Scene::estimatedDurationMs).sum())
                    .isEqualTo(pair.getKey().estimatedDurationMs());
        }
        // the second script was compared with the first adopted script of the channel and is not a repeat
        assertThat(codes(result(second, ProductionStage.SCRIPT), "BLOCK")).isEmpty();
    }

    @Test
    void recommendationsOnlyAreKeptAndTheRequestStillCompletes() throws Exception {
        port.corrupt = Map.of(ProductionStage.SCRIPT, body -> {
            Script script = (Script) body;
            List<Segment> segments = new ArrayList<>(script.segments());
            segments.set(0, new Segment("s1", "This little cube on the kitchen scale looks a lot like sugar.",
                    List.of()));
            return new Script(script.language(), segments, script.hookSegmentIds(),
                    com.aistudio.production.stage.SpeechTiming.estimateMs(segments), script.timingRuleVersion(),
                    script.editorialIntent());
        });
        long id = shorts(VENUS);
        drain();
        assertStatus(id, "SUCCEEDED");
        JsonNode script = result(planning(id), ProductionStage.SCRIPT);
        assertThat(codes(script, "BLOCK")).isEmpty();
        assertThat(codes(script, "RECOMMENDATION")).contains("HOOK_TOO_LONG", "HOOK_WEAK");
        assertThat(step(id, ProductionStage.SCENES).get("status")).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ required blocks stop the request

    @Test
    void insufficientCoreEvidenceBlocksResearchAndNothingLaterRuns() throws Exception {
        long id = shorts(NO_FIXTURE);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.RESEARCH).get("status")).isEqualTo("FAILED");
        assertThat(step(id, ProductionStage.RESEARCH).get("last_error_code")).isEqualTo("PLANNING_VALIDATION_BLOCKED");
        for (ProductionStage later : List.of(ProductionStage.SCRIPT, ProductionStage.SCENES, ProductionStage.IMAGE,
                ProductionStage.QC)) {
            assertThat(step(id, later).get("status")).isEqualTo("PENDING");
            assertThat(operations(id, later)).isZero();
        }
        assertThat(port.generated(ProductionStage.SCRIPT)).isZero();
        JsonNode planning = planning(id);
        assertThat(planning.path("results")).hasSize(1);
        assertThat(codes(result(planning, ProductionStage.RESEARCH), "BLOCK")).contains("CORE_EVIDENCE_INSUFFICIENT");
        // the receipt was applied with the review, so the cost state is final
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND applied_at IS NULL
                """, Integer.class, id)).isZero();
    }

    @Test
    void fabricatedSourceBlocksResearch() throws Exception {
        port.corrupt = Map.of(ProductionStage.RESEARCH, body -> {
            Research r = (Research) body;
            List<Source> sources = r.sources().stream().map(s -> new Source(s.id(), s.origin(), s.status(),
                    "https://fixtures.ai-studio.invalid/made-up/" + s.id().replace(':', '-'), s.title(),
                    s.publisher(), s.checkedAtUtc(), s.documentHash())).toList();
            return new Research(r.question(), r.angle(), r.investigations(), r.claims(), sources, r.limitations(),
                    r.scriptClaimIds());
        });
        long id = shorts(NEUTRON);
        drain();
        assertStatus(id, "FAILED");
        assertThat(codes(result(planning(id), ProductionStage.RESEARCH), "BLOCK")).contains("SOURCE_NOT_FOUND");
        assertThat(step(id, ProductionStage.SCRIPT).get("status")).isEqualTo("PENDING");
    }

    @Test
    void hypothesisStatedAsFactBlocksScriptAndScenesNeverRun() throws Exception {
        port.corrupt = Map.of(ProductionStage.SCRIPT, body -> {
            Script s = (Script) body;
            List<Segment> segments = new ArrayList<>(s.segments());
            segments.replaceAll(seg -> seg.id().equals("s6")
                    ? new Segment("s6", "Deeper inside, the matter breaks down into free quarks.", seg.claimIds())
                    : seg);
            return new Script(s.language(), segments, s.hookSegmentIds(),
                    com.aistudio.production.stage.SpeechTiming.estimateMs(segments), s.timingRuleVersion(),
                    s.editorialIntent());
        });
        long id = shorts(NEUTRON);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.SCRIPT).get("last_error_code")).isEqualTo("PLANNING_VALIDATION_BLOCKED");
        assertThat(step(id, ProductionStage.SCENES).get("status")).isEqualTo("PENDING");
        assertThat(operations(id, ProductionStage.SCENES)).isZero();
        assertThat(codes(result(planning(id), ProductionStage.SCRIPT), "BLOCK")).containsExactly("HYPOTHESIS_AS_FACT");
    }

    @Test
    void sceneThatDropsASegmentBlocksScenes() throws Exception {
        port.corrupt = Map.of(ProductionStage.SCENES, body -> {
            Scenes s = (Scenes) body;
            return new Scenes(s.visualStyle(), s.scenes().subList(0, s.scenes().size() - 1));
        });
        long id = shorts(VENUS);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.IMAGE).get("status")).isEqualTo("PENDING");
        assertThat(codes(result(planning(id), ProductionStage.SCENES), "BLOCK"))
                .contains("SCENE_SEGMENT_MISSING", "SCENE_DURATION_TOTAL");
    }

    // ------------------------------------------------------------------ recovery and fencing

    @Test
    void failedApplyRollsBackAndRecoveryReappliesTheSameOperationWithoutRegeneration() throws Exception {
        long id = shorts(NEUTRON);
        boolean[] failed = { false };
        port.beforeApply = operation -> {
            if (!failed[0]) {
                failed[0] = true;
                throw new IllegalStateException("simulated failure inside the fenced apply");
            }
        };
        drain();
        assertThat(step(id, ProductionStage.RESEARCH).get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(step(id, ProductionStage.RESEARCH).get("last_error_code")).isEqualTo("RECEIPT_APPLY_FAILED");
        assertStatus(id, "RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_stage_result r JOIN production_step s ON s.id = r.step_id
                WHERE r.request_id = ? AND s.stage = 'RESEARCH' AND r.validation_json IS NOT NULL
                """, Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND applied_at IS NULL
                """, Integer.class, id)).isEqualTo(1);
        makeDue(id);
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isEqualTo(1);
        assertThat(port.generated(ProductionStage.RESEARCH)).isEqualTo(1);
        assertThat(((Number) step(id, ProductionStage.RESEARCH).get("attempts")).intValue()).isEqualTo(1);
    }

    @Test
    void staleLeaseCannotApplyAPlanningReceipt() throws Exception {
        long id = shorts(VENUS);
        boolean[] stolen = { false };
        port.beforeResponse = owner -> {
            if (!stolen[0]) {
                stolen[0] = true; // another worker takes over after the dispatch
                jdbc.update("UPDATE production_request SET lease_epoch = lease_epoch + 1 WHERE id = ?", id);
            }
        };
        assertThat(executor.runOnce("stale-worker")).isTrue();
        assertThat(step(id, ProductionStage.RESEARCH).get("status")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND applied_at IS NULL
                """, Integer.class, id)).isEqualTo(1);
        jdbc.update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isEqualTo(1);
        assertThat(port.generated(ProductionStage.RESEARCH)).isEqualTo(1);
    }

    @Test
    void cancelAfterResearchStopsFurtherPlanningDispatch() throws Exception {
        long id = shorts(TARDIGRADE);
        port.beforeApply = operation -> jdbc.update(
                "UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ? AND "
                        + "cancel_requested_at IS NULL", id);
        drain();
        assertStatus(id, "CANCELLED");
        assertThat(operations(id, ProductionStage.SCRIPT)).isZero();
        assertThat(port.generated(ProductionStage.SCRIPT)).isZero();
    }

    // ------------------------------------------------------------------ compatibility

    @Test
    void earlierPipelinesKeepTheirExecutionAndClaimRules() throws Exception {
        // requests stored before V2-03 keep the pipeline version they were created with
        long fixed = queued(TARDIGRADE);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-fixed-1' WHERE id = ?", fixed);
        drain();
        assertStatus(fixed, "SUCCEEDED");
        assertThat(step(fixed, ProductionStage.DISCOVER).get("result_ref").toString()).startsWith("fake:");
        long discovery = shorts(NEUTRON);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-discovery-1' WHERE id = ?", discovery);
        drain();
        assertStatus(discovery, "SUCCEEDED");
        assertThat(port.generated(ProductionStage.RESEARCH)).isZero();
        assertThat(planning(discovery).path("results")).isEmpty();
        assertThat(planning(fixed).path("results")).isEmpty();
        assertThat(step(discovery, ProductionStage.RESEARCH).get("result_ref").toString()).doesNotStartWith("planning:");
    }

    @Test
    void executorWithoutThePlanningFlowNeverClaimsPlanningRequests() throws Exception {
        long id = queued(NEUTRON);
        try (ProductionExecutor withoutPlanning = new ProductionExecutor(executionMapper, budgetControl, transactions,
                cost, selection, new FakeStageWork(BigDecimal.ONE), properties,
                new DiscoveryFlow(discoveryPort, new RuleBasedDiscoveryEvaluator()))) {
            assertThat(withoutPlanning.runOnce("no-planning")).isFalse();
        }
        assertStatus(id, "QUEUED");
        assertThat(operations(id, ProductionStage.DISCOVER)).isZero();
    }
}
