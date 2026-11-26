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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import com.aistudio.content.ContentService;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.discovery.DiscoveryFixtures;
import com.aistudio.production.discovery.DiscoverySelections;
import com.aistudio.production.discovery.FakeDiscoveryProvider;
import com.aistudio.production.discovery.RuleBasedDiscoveryEvaluator;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;
import com.aistudio.topic.TopicNormalizer;
import com.aistudio.topic.TopicService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2-02 DISCOVER/SELECT in the executor against an isolated MariaDB (V1~V11). Requests are created through the
 * production API; DISCOVER, SELECT and settlement run through the real discovery persistence/cost adapter, the
 * real V2-01 cost port and the real selection port. Only the discovery provider is a scripted FAKE, and failures
 * are injected by {@link InjectingDiscoveryPort} just before a delegated side effect, on the same ports and DB.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false",
        "ai-studio.production.executor.lease-duration=PT10S",
        "ai-studio.production.executor.max-leases=2",
        "ai-studio.production.executor.recovery-recheck=PT1H",
        "ai-studio.production.executor.retry-base=PT1S",
        "ai-studio.production.executor.retry-cap=PT4S" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(DiscoveryExecutionIT.Ports.class)
class DiscoveryExecutionIT {
    private static final String PATH = "/api/v1/production-requests";
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final TypeReference<List<EvaluatedCandidate>> EVALUATIONS = new TypeReference<>() {};

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
        @Primary
        InjectingDiscoveryPort injectingDiscoveryPort(
                @Qualifier("productionDiscoveryService") DiscoveryExecutionPort real) {
            return new InjectingDiscoveryPort(real);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired TopicService topics;
    @Autowired ContentService contents;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionExecutor executor;
    @Autowired InjectingDiscoveryPort port;
    @Autowired ScriptedDiscoveryProvider provider;
    @Autowired JsonMapper json;
    @Autowired ExecutionMapper executionMapper;
    @Autowired BudgetControlLock budgetControl;
    @Autowired LockRetryingTransactions transactions;
    @Autowired ExecutionProperties properties;
    @Autowired ProductionCostPort cost;
    @Autowired ProductionSelectionPort selection;
    AdminPrincipal admin;

    @BeforeAll
    void enableIsolatedBudgets() {
        admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated discovery integration", control.get().version(), actor);
    }

    @BeforeEach
    void isolate() {
        port.reset();
        provider.reset();
        jdbc.update("""
                UPDATE production_request SET status = 'FAILED', lease_owner = NULL, lease_until = NULL
                WHERE status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
                """);
        if (!control.get().spendingEnabled()) {
            control.setSpending(true, "re-enable", control.get().version(), Actor.admin(admin.id()));
        }
    }

    // ------------------------------------------------------------------ helpers

    long channel(List<String> prohibited) {
        Actor actor = Actor.admin(admin.id());
        ChannelSettings settings = new ChannelSettings("SHORTS", 40, 60, 480, 720,
                "English science Shorts that explain one surprising question with visuals",
                "Curious teens and adults", null, null, true,
                Map.of("discovery", Map.of("prohibitedTopics", prohibited)));
        long id = channels.create(ApiTestSupport.uniqueKey("ifnova"), "en", "UTC", settings, actor).channel().id();
        policies.create("CONTENT_TYPE", id, "SHORTS", "LIFETIME", new BigDecimal("100"), "UTC", null, actor);
        return id;
    }

    long create(long channel, String body) throws Exception {
        String key = ApiTestSupport.uniqueKey("discovery");
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\"" + body + "}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
    }

    int drain(String owner) {
        return drain(executor, owner);
    }

    static int drain(ProductionExecutor executor, String owner) {
        int claims = 0;
        while (executor.runOnce(owner)) {
            if (++claims > 50) {
                throw new AssertionError("executor did not settle");
            }
        }
        return claims;
    }

    ProductionExecutor restartedExecutor() {
        return new ProductionExecutor(executionMapper, budgetControl, transactions, cost, selection,
                new FakeStageWork(BigDecimal.ONE), properties, new DiscoveryFlow(port, new RuleBasedDiscoveryEvaluator()));
    }

    String requestStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id);
    }

    Map<String, Object> step(long id, String stage) {
        return jdbc.queryForMap("SELECT * FROM production_step WHERE production_request_id = ? AND stage = ?", id,
                stage);
    }

    Map<String, Object> batch(long id) {
        return jdbc.queryForMap("SELECT * FROM production_discovery WHERE production_request_id = ?", id);
    }

    /** The evaluation stored at DISCOVER; never overwritten by a later re-check. */
    List<EvaluatedCandidate> evaluations(long id) {
        return json.readValue(batch(id).get("evaluation_json").toString(), EVALUATIONS);
    }

    /** The latest SELECT re-check, or null when SELECT has not re-checked yet. */
    List<EvaluatedCandidate> rechecks(long id) {
        Object recheck = batch(id).get("recheck_json");
        return recheck == null ? null : json.readValue(recheck.toString(), EVALUATIONS);
    }

    static EvaluatedCandidate find(List<EvaluatedCandidate> list, int candidateId) {
        return list.stream().filter(e -> e.candidateId() == candidateId).findFirst().orElseThrow();
    }

    int operations(long id, String stage) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = ?
                """, Integer.class, id, stage);
    }

    String linkedTitle(long id) {
        return jdbc.queryForObject("""
                SELECT t.title FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN topic t ON t.id = c.topic_id WHERE p.id = ?
                """, String.class, id);
    }

    void makeDue(long id) {
        jdbc.update("""
                UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE production_request_id = ? AND retry_at IS NOT NULL
                """, id);
    }

    void insertTopic(long channel, String title) {
        String normalized = TopicNormalizer.normalize(title);
        jdbc.update("""
                INSERT INTO topic (channel_id, title, normalized_title, normalized_hash, normalization_version,
                    created_by, created_at) VALUES (?, ?, ?, ?, 1, ?, UTC_TIMESTAMP(6))
                """, channel, title, normalized, TopicNormalizer.hash(TopicNormalizer.VERSION, normalized), admin.id());
    }

    JsonNode discoveryView(long id) throws Exception {
        return json.readTree(mvc.perform(get(PATH + "/" + id + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andReturn().getResponse().getContentAsString());
    }

    /** Chooses with the version and batch token the requester last received. */
    void choose(long id, int candidate, int expectedStatus) throws Exception {
        JsonNode view = discoveryView(id);
        choose(id, candidate, view.path("version").asInt(), view.path("batchToken").asString(), expectedStatus);
    }

    void choose(long id, int candidate, int version, String token, int expectedStatus) throws Exception {
        mvc.perform(post(PATH + "/" + id + "/selection").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":%d,\"version\":%d,\"batchToken\":\"%s\"}"
                                .formatted(candidate, version, token)))
                .andExpect(status().is(expectedStatus));
    }

    boolean leased(long id) {
        return jdbc.queryForObject("SELECT lease_owner IS NOT NULL FROM production_request WHERE id = ?",
                Boolean.class, id);
    }

    // ------------------------------------------------------------------ AUTO

    @Test
    void autoDiscoversEvaluatesSelectsTheBestCandidateAndFinishesElevenStages() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        // the request keeps the language frozen at creation even if the channel changes afterwards
        jdbc.update("UPDATE channel SET language = 'ko' WHERE id = ?", channel);
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT status FROM production_step WHERE production_request_id = ?",
                String.class, id)).hasSize(11).containsOnly("SUCCEEDED");
        assertThat(step(id, "DISCOVER").get("result_ref")).isEqualTo("discovery:" + id);
        List<EvaluatedCandidate> evaluated = evaluations(id);
        assertThat(evaluated).hasSize(8).allSatisfy(e -> assertThat(e.totalScore()).isBetween(0, 100));
        EvaluatedCandidate best = DiscoverySelections.best(evaluated).orElseThrow();
        Map<String, Object> batch = batch(id);
        assertThat(((Number) batch.get("selected_candidate_id")).intValue()).isEqualTo(best.candidateId());
        assertThat((String) batch.get("selection_reason")).startsWith("AUTO: highest ranked");
        assertThat(rechecks(id)).as("the SELECT re-check is stored next to the first evaluation").hasSize(8);
        assertThat(linkedTitle(id)).isEqualTo(best.candidate().title());
        Map<String, Object> revision = jdbc.queryForMap("""
                SELECT v.brief, v.language FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN content_revision v ON v.id = c.current_revision_id WHERE p.id = ?
                """, id);
        assertThat((String) revision.get("brief")).startsWith("Question: " + best.candidate().centralQuestion());
        assertThat(revision.get("language")).isEqualTo("en");
        assertThat(provider.generations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND status = 'SETTLED'
                    AND applied_at IS NOT NULL
                """, Integer.class, id)).isEqualTo(8);

        mvc.perform(get(PATH + "/" + id + "/discovery").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionMode").value("AUTO"))
                .andExpect(jsonPath("$.awaitingSelection").value(false))
                .andExpect(jsonPath("$.candidates.length()").value(8))
                .andExpect(jsonPath("$.selectedCandidateId").value(best.candidateId()))
                .andExpect(ApiTestSupport.matchesContract());
        mvc.perform(get(PATH).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(id)))
                .andExpect(jsonPath("$.items[0].selectedTitle").value(best.candidate().title()));
    }

    @Test
    void autoSkipsACandidateThatBecameADuplicateBeforeSelectAndKeepsBothEvaluations() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        AtomicBoolean once = new AtomicBoolean();
        port.beforeLock = requestId -> {
            if (once.compareAndSet(false, true)) {
                EvaluatedCandidate best = DiscoverySelections.best(evaluations(requestId)).orElseThrow();
                insertTopic(channel, best.candidate().title()); // another request linked it meanwhile
            }
        };
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(evaluations(id)); // stored DISCOVER ranking
        int selected = ((Number) batch(id).get("selected_candidate_id")).intValue();
        assertThat(ranked.get(0).candidateId()).isEqualTo(firstRankedAtDiscover(id));
        assertThat(selected).as("the first ranked candidate was blocked at SELECT, the next one is linked")
                .isEqualTo(ranked.get(1).candidateId());
        assertThat(linkedTitle(id)).isEqualTo(ranked.get(1).candidate().title());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ? AND title = ?",
                Integer.class, channel, ranked.get(0).candidate().title())).isEqualTo(1);
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(1);
        assertThat(find(evaluations(id), ranked.get(0).candidateId()).eligible())
                .as("the first evaluation is preserved").isTrue();
        EvaluatedCandidate rechecked = find(rechecks(id), ranked.get(0).candidateId());
        assertThat(rechecked.blockCodes()).contains(Block.DUPLICATE);
        assertThat(rechecked.comparisons()).anySatisfy(c -> assertThat(c.reason()).isEqualTo("EXACT_TITLE"));
    }

    @Test
    void autoWithEveryCandidateBlockedAtSelectRecordsTheLatestReasonsAndFails() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, ",\"candidateCount\":5");
        AtomicBoolean once = new AtomicBoolean();
        port.beforeLock = requestId -> {
            if (once.compareAndSet(false, true)) {
                evaluations(requestId).forEach(e -> insertTopic(channel, e.candidate().title()));
            }
        };
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("FAILED");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("FAILED");
        assertThat(step(id, "SELECT").get("last_error_code")).isEqualTo("DISCOVERY_NO_ELIGIBLE_CANDIDATE");
        assertThat(port.exhausted.get()).isEqualTo(1);
        assertThat(evaluations(id)).as("first evaluation kept").anySatisfy(e -> assertThat(e.eligible()).isTrue());
        assertThat(rechecks(id)).hasSize(5).allSatisfy(e -> assertThat(e.blockCodes()).contains(Block.DUPLICATE));
        assertThat(batch(id).get("selected_candidate_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT content_id FROM production_request WHERE id = ?", Long.class, id))
                .isNull();
    }

    /** Ranking of the DISCOVER evaluation, reproduced from the stored response on an empty catalog. */
    private int firstRankedAtDiscover(long id) {
        Input input = port.input(id);
        Response response = new FakeDiscoveryProvider().generate(input);
        return DiscoverySelections.best(new RuleBasedDiscoveryEvaluator().evaluate(input, response, List.of()))
                .orElseThrow().candidateId();
    }

    @Test
    void allCandidatesBlockedFailsDiscoverWithoutFurtherStagesOrRegeneration() throws Exception {
        long channel = channel(List.of());
        provider.responses = input -> withClaims(new FakeDiscoveryProvider().generate(input));
        long id = create(channel, "");
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("FAILED");
        assertThat(step(id, "DISCOVER").get("status")).isEqualTo("FAILED");
        assertThat(step(id, "DISCOVER").get("last_error_code")).isEqualTo("DISCOVERY_NO_ELIGIBLE_CANDIDATE");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(evaluations(id)).hasSize(8).allSatisfy(e -> assertThat(e.eligible()).isFalse());
        assertThat(operations(id, "DISCOVER")).isEqualTo(1);
        assertThat(provider.generations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ?",
                Integer.class, id)).as("no later stage and no second discovery").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT applied_at IS NOT NULL FROM provider_operation WHERE production_request_id = ?
                """, Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT content_id FROM production_request WHERE id = ?", Long.class, id))
                .isNull();
    }

    @Test
    void malformedProviderResponseConfirmsTheCostAndFailsWithoutStoringOrEvaluating() throws Exception {
        long channel = channel(List.of());
        provider.responses = input -> {
            Response full = new FakeDiscoveryProvider().generate(input);
            return new Response(full.version(), full.candidates().subList(0, 4)); // wrong count
        };
        long id = create(channel, "");
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("FAILED");
        assertThat(step(id, "DISCOVER").get("status")).isEqualTo("FAILED");
        assertThat(step(id, "DISCOVER").get("last_error_code")).isEqualTo("EFFECT_EXECUTED_FAILED");
        assertThat(batch(id).get("response_json")).as("a malformed body is never stored").isNull();
        assertThat(batch(id).get("evaluation_json")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT o.applied_at IS NOT NULL AND r.recognized_cost_krw = 1 AND r.reserved_remaining_krw = 0
                FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id
                WHERE o.production_request_id = ?
                """, Boolean.class, id)).as("the executed cost is confirmed and applied").isTrue();
        assertThat(provider.generations.get()).isEqualTo(1);
        mvc.perform(get(PATH + "/" + id).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andExpect(jsonPath("$.steps[0].lastErrorCode").value("EFFECT_EXECUTED_FAILED"))
                .andExpect(jsonPath("$.recognizedKrw").value("1.000000"));
    }

    @Test
    void spendingSwitchOffRejectsDiscoverBeforeAnyDispatch() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        control.setSpending(false, "discovery blocked", control.get().version(), Actor.admin(admin.id()));
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("FAILED");
        assertThat(step(id, "DISCOVER").get("last_error_code")).isEqualTo("EFFECT_REJECTED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND dispatched_at IS NOT NULL
                """, Integer.class, id)).isZero();
        assertThat(provider.generations.get()).isZero();
    }

    @Test
    void unknownDiscoverOutcomeKeepsTheReservationAndNeverRedispatches() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        provider.failAfterDispatch = true;
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(step(id, "DISCOVER").get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT r.reserved_remaining_krw > 0 FROM provider_operation o
                JOIN budget_reservation r ON r.id = o.reservation_id WHERE o.production_request_id = ?
                """, Boolean.class, id)).isTrue();
        assertThat(leased(id)).isFalse();
        assertThat(provider.generations.get()).isEqualTo(1);

        provider.failAfterDispatch = false;
        makeDue(id);
        try (ProductionExecutor restarted = restartedExecutor()) {
            drain(restarted, "w2"); // restart: the same key is re-presented, the port reports it unresolved again
        }
        assertThat(operations(id, "DISCOVER")).isEqualTo(1);
        assertThat(step(id, "DISCOVER").get("attempts")).as("recovery does not count an attempt").isEqualTo(1);
        assertThat(provider.generations.get()).as("an unknown outcome is never sent again").isEqualTo(1);
        assertThat(requestStatus(id)).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test
    void failureBeforeTheEvaluationCommitReplaysTheStoredResponseAfterRestart() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        AtomicBoolean once = new AtomicBoolean();
        port.beforeApply = requestId -> {
            if (once.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated failure in the evaluation transaction");
            }
        };
        drain("w1");
        assertThat(step(id, "DISCOVER").get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(step(id, "DISCOVER").get("last_error_code")).isEqualTo("RECEIPT_APPLY_FAILED");
        String hash = (String) batch(id).get("response_hash");
        assertThat(hash).isNotNull();
        assertThat(batch(id).get("evaluation_json")).isNull();

        makeDue(id);
        try (ProductionExecutor restarted = restartedExecutor()) {
            drain(restarted, "w2");
        }
        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(batch(id).get("response_hash")).isEqualTo(hash);
        assertThat(provider.generations.get()).as("the stored response is replayed, not regenerated").isEqualTo(1);
        assertThat(operations(id, "DISCOVER")).isEqualTo(1);
    }

    @Test
    void selectFailureRollsBackTheLinkAndRetriesAsALocalAttempt() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, "");
        AtomicBoolean once = new AtomicBoolean();
        port.beforeRecord = requestId -> {
            if (once.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated failure after link");
            }
        };
        drain("w1");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("RETRY_WAIT");
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT content_id, run_id, selection_hash FROM production_request WHERE id = ?",
                id)).containsEntry("content_id", null).containsEntry("run_id", null).containsEntry("selection_hash", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channel))
                .as("Topic, Content, Revision and Run are rolled back together").isZero();
        assertThat(batch(id).get("selected_candidate_id")).isNull();
        assertThat(batch(id).get("recheck_json")).as("the re-check is rolled back with the link").isNull();

        makeDue(id);
        drain("w1");
        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(1);
    }

    @Test
    void twoRequestsRacingForTheSameCandidateNeverCreateTheSameTopicTwice() throws Exception {
        long channel = channel(List.of());
        // both requests receive the same batch, so their best candidate is the same
        provider.responses = input -> new FakeDiscoveryProvider().generate(new Input(1, input.channelConfigId(),
                input.language(), input.candidateCount(), input.minDurationSeconds(), input.maxDurationSeconds(),
                input.strategy(), input.audience(), input.prohibitedTopics(), input.rulesVersion()));
        long first = create(channel, "");
        long second = create(channel, "");

        race(List.of("race-1", "race-2"));
        drain("race-tail");

        assertThat(requestStatus(first)).isEqualTo("SUCCEEDED");
        assertThat(requestStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(linkedTitle(first)).isNotEqualTo(linkedTitle(second));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) = COUNT(DISTINCT normalized_hash) FROM topic WHERE channel_id = ?
                """, Boolean.class, channel)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(2);
    }

    private void race(List<String> owners) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(owners.size());
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (String owner : owners) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return drain(owner);
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final Candidate SKY = DiscoveryFixtures.candidate("Rayleigh scattering",
            "Why is the daytime sky blue instead of violet?", "Why the Sky Is Blue, Not Violet",
            "Walk through the scattering mechanism one step at a time with a simple diagram.");
    /** The same question and angle in different words and a different title (no exact Topic match). */
    private static final Candidate SKY_PARAPHRASE = DiscoveryFixtures.candidate("Rayleigh scattering",
            "Why does the daytime atmosphere look azure rather than violet?", "The Azure Atmosphere Puzzle",
            "Walk through the scattering mechanism step by step with a simple diagram.");

    @Test
    void concurrentSelectOfDifferentlyWordedDuplicatesLinksOnlyOne() throws Exception {
        long channel = channel(List.of());
        long first = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":5");
        long second = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":5");
        provider.responses = input -> DiscoveryFixtures.response(5, input.requestId() == first ? SKY : SKY_PARAPHRASE);
        drain("w1");
        assertThat(step(first, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(step(second, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(find(evaluations(first), 1).eligible()).isTrue();
        assertThat(find(evaluations(second), 1).eligible()).as("no conflict before either is linked").isTrue();
        choose(first, 1, 200);
        choose(second, 1, 200);

        // both SELECT transactions hold their request lock before either takes the channel lock
        CyclicBarrier bothLocked = new CyclicBarrier(2);
        Set<Long> arrived = ConcurrentHashMap.newKeySet();
        port.beforeLock = requestId -> {
            if (arrived.add(requestId)) {
                try {
                    bothLocked.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        race(List.of("race-1", "race-2"));

        List<Long> linked = List.of(first, second).stream()
                .filter(id -> "SUCCEEDED".equals(requestStatus(id))).toList();
        assertThat(linked).as("exactly one of the substantive duplicates is linked").hasSize(1);
        long loser = linked.get(0) == first ? second : first;
        assertThat(requestStatus(loser)).isEqualTo("RUNNING");
        assertThat(step(loser, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(step(loser, "SELECT").get("attempts")).isEqualTo(0);
        assertThat(batch(loser).get("chosen_candidate_id")).isNull();
        assertThat(find(rechecks(loser), 1).blockCodes()).containsExactly(Block.DUPLICATE);
        assertThat(find(evaluations(loser), 1).eligible()).as("the first evaluation is preserved").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(1);
        assertThat(leased(loser)).isFalse();
        assertThat(discoveryView(loser).path("awaitingSelection").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ MANUAL

    @Test
    void manualReviewMustBeConfirmedAgainWhenAnotherContentAddsANewUncertainComparison() throws Exception {
        long channel = channel(List.of());
        String brief = "Question: Why is the daytime sky blue instead of violet?\n"
                + "Angle: Walk through the scattering mechanism one step at a time with a simple diagram.\n"
                + "Core topic: Rayleigh scattering";
        Actor actor = Actor.admin(admin.id());
        var firstTopic = topics.create(new TopicService.CreateTopic(ApiTestSupport.uniqueKey("topic"), channel,
                "Why the Sky Is Blue, Not Violet", null), actor).topic();
        var firstContent = contents.create(new ContentService.CreateContent(ApiTestSupport.uniqueKey("content"),
                channel, firstTopic.id(), "SHORTS", firstTopic.title(), brief, "en", 50, null, null, null), actor)
                .view();
        Candidate borderline = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why is the daytime sky blue at noon?", "Noon Sky Blue Explained Simply",
                "Rayleigh scattering of the blue daytime sky.");
        long id = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":5");
        provider.responses = input -> DiscoveryFixtures.response(5, borderline);
        drain("review-first");

        String originalEvaluation = batch(id).get("evaluation_json").toString();
        EvaluatedCandidate initial = find(evaluations(id), 1);
        assertThat(initial.blockCodes()).containsExactly(Block.SIMILARITY_UNCERTAIN);
        assertThat(initial.comparisons()).anySatisfy(c -> {
            assertThat(c.contentId()).isEqualTo(Long.toString(firstContent.content().id()));
            assertThat(c.reason()).endsWith(" SIMILARITY_UNCERTAIN");
        });
        JsonNode before = discoveryView(id);
        int oldVersion = before.path("version").asInt();
        String oldToken = before.path("batchToken").asString();
        confirmChoice(id, 1, oldVersion, oldToken, 200);
        assertThat(provider.generations.get()).isEqualTo(1);
        assertThat(operations(id, "DISCOVER")).isEqualTo(1);
        BigDecimal discoveryCost = jdbc.queryForObject("""
                SELECT b.recognized_cost_krw FROM provider_operation o
                JOIN production_step s ON s.id = o.production_step_id
                JOIN budget_reservation b ON b.id = o.reservation_id
                WHERE o.production_request_id = ? AND s.stage = 'DISCOVER'
                """, BigDecimal.class, id);

        // These service calls commit on the test thread after the choice and before the SELECT transaction.
        var secondTopic = topics.create(new TopicService.CreateTopic(ApiTestSupport.uniqueKey("topic"), channel,
                "A Different Existing Sky Explanation", null), actor).topic();
        var secondContent = contents.create(new ContentService.CreateContent(ApiTestSupport.uniqueKey("content"),
                channel, secondTopic.id(), "SHORTS", secondTopic.title(), brief, "en", 50, null, null, null), actor)
                .view();
        drain("review-recheck");

        assertThat(requestStatus(id)).isEqualTo("RUNNING");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(0);
        assertThat(batch(id).get("chosen_candidate_id")).isNull();
        assertThat(batch(id).get("evaluation_json").toString()).isEqualTo(originalEvaluation);
        assertThat(jdbc.queryForMap("SELECT content_id, run_id FROM production_request WHERE id = ?", id))
                .containsEntry("content_id", null).containsEntry("run_id", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_revision WHERE channel_id = ?", Integer.class,
                channel)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_run WHERE content_id IN (?, ?)", Integer.class,
                firstContent.content().id(), secondContent.content().id())).isZero();
        EvaluatedCandidate refreshed = find(rechecks(id), 1);
        assertThat(refreshed.blockCodes()).containsExactly(Block.SIMILARITY_UNCERTAIN);
        assertThat(refreshed.comparisons()).anySatisfy(c -> {
            assertThat(c.contentId()).isEqualTo(Long.toString(secondContent.content().id()));
            assertThat(c.revisionId()).isEqualTo(Long.toString(secondContent.currentRevision().id()));
            assertThat(c.reason()).endsWith(" SIMILARITY_UNCERTAIN");
        });
        assertThat(leased(id)).isFalse();
        assertThat(executor.runOnce("review-wait")).isFalse();
        confirmChoice(id, 1, oldVersion, oldToken, 409);
        JsonNode updated = discoveryView(id);
        assertThat(updated.path("awaitingSelection").asBoolean()).isTrue();
        assertThat(updated.path("version").asInt()).isGreaterThan(oldVersion);
        assertThat(updated.path("batchToken").asString()).isNotEqualTo(oldToken);
        assertThat(updated.path("candidates").get(0).path("comparisons").toString())
                .contains(Long.toString(secondContent.content().id()), "SIMILARITY_UNCERTAIN");
        confirmChoice(id, 1, updated.path("version").asInt(), oldToken, 409);
        choose(id, 1, updated.path("version").asInt(), updated.path("batchToken").asString(), 422);
        confirmChoice(id, 1, updated.path("version").asInt(), updated.path("batchToken").asString(), 200);
        drain("review-confirmed");

        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(linkedTitle(id)).isEqualTo(borderline.title());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN content_revision r ON r.id = c.current_revision_id
                JOIN content_run run ON run.id = p.run_id AND run.content_revision_id = r.id
                WHERE p.id = ? AND c.channel_id = ?
                """, Integer.class, id, channel)).isEqualTo(1);
        assertThat(operations(id, "DISCOVER")).isEqualTo(1);
        assertThat(provider.generations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT b.recognized_cost_krw FROM provider_operation o
                JOIN production_step s ON s.id = o.production_step_id
                JOIN budget_reservation b ON b.id = o.reservation_id
                WHERE o.production_request_id = ? AND s.stage = 'DISCOVER'
                """, BigDecimal.class, id)).isEqualByComparingTo(discoveryCost);
        assertThat(batch(id).get("evaluation_json").toString()).isEqualTo(originalEvaluation);
    }

    private void confirmChoice(long id, int candidate, int version, String token, int expectedStatus) throws Exception {
        mvc.perform(post(PATH + "/" + id + "/selection").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":%d,\"version\":%d,\"batchToken\":\"%s\",\"confirmReview\":true}"
                                .formatted(candidate, version, token)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void manualWaitsWithoutALeaseWhileOtherRequestsRunAndResumesAfterTheChoice() throws Exception {
        long channel = channel(List.of());
        long manual = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":5");
        long auto = create(channel(List.of()), "");
        drain("w1");

        assertThat(requestStatus(auto)).as("another request is claimed and finishes").isEqualTo("SUCCEEDED");
        assertThat(requestStatus(manual)).isEqualTo("RUNNING");
        assertThat(step(manual, "DISCOVER").get("status")).isEqualTo("SUCCEEDED");
        assertThat(step(manual, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(step(manual, "SELECT").get("attempts")).isEqualTo(0);
        assertThat(jdbc.queryForMap("SELECT lease_owner, lease_until FROM production_request WHERE id = ?", manual))
                .containsEntry("lease_owner", null).containsEntry("lease_until", null);
        assertThat(executor.runOnce("w1")).as("the waiting request is not claimed").isFalse();
        try (ProductionExecutor restarted = restartedExecutor()) {
            assertThat(restarted.runOnce("after-restart")).as("the wait survives a restart").isFalse();
        }
        JsonNode view = discoveryView(manual);
        assertThat(view.path("awaitingSelection").asBoolean()).isTrue();
        assertThat(view.path("candidates").size()).isEqualTo(5);

        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(evaluations(manual));
        EvaluatedCandidate pick = ranked.get(ranked.size() - 1); // the requester may prefer a lower-ranked one
        int other = ranked.get(0).candidateId() == pick.candidateId() ? ranked.get(1).candidateId()
                : ranked.get(0).candidateId();
        int version = view.path("version").asInt();
        String token = view.path("batchToken").asString();
        choose(manual, pick.candidateId(), version, "0".repeat(64), 409); // a token of no batch
        choose(manual, pick.candidateId(), version, token, 200);
        choose(manual, pick.candidateId(), version, token, 200); // the same choice again returns the stored result
        choose(manual, other, version, token, 409);
        JsonNode chosen = discoveryView(manual);
        choose(manual, other, chosen.path("version").asInt(), chosen.path("batchToken").asString(), 409);
        drain("w1");

        assertThat(requestStatus(manual)).isEqualTo("SUCCEEDED");
        Map<String, Object> batch = batch(manual);
        assertThat(((Number) batch.get("selected_candidate_id")).intValue()).isEqualTo(pick.candidateId());
        assertThat((String) batch.get("selection_reason")).startsWith("MANUAL:");
        assertThat(linkedTitle(manual)).isEqualTo(pick.candidate().title());
        assertThat(step(manual, "SELECT").get("attempts")).isEqualTo(1);
    }

    @Test
    void cancelWhileWaitingFinishesWithoutLinking() throws Exception {
        long id = create(channel(List.of()), ",\"selectionMode\":\"MANUAL\"");
        drain("w1");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("PENDING");
        JsonNode view = discoveryView(id);

        mvc.perform(post(PATH + "/" + id + "/cancel").with(ApiTestSupport.as(admin))).andExpect(status().isOk());
        drain("w1");
        assertThat(requestStatus(id)).isEqualTo("CANCELLED");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT content_id FROM production_request WHERE id = ?", Long.class, id))
                .isNull();
        EvaluatedCandidate any = DiscoverySelections.best(evaluations(id)).orElseThrow();
        choose(id, any.candidateId(), view.path("version").asInt(), view.path("batchToken").asString(), 409);
    }

    @Test
    void aChoiceInvalidatedByTheLatestCatalogWaitsAgainWithoutUsingAnAttempt() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":6");
        drain("w1");
        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(evaluations(id));
        EvaluatedCandidate pick = ranked.get(0);
        JsonNode before = discoveryView(id);
        choose(id, pick.candidateId(), 200);
        insertTopic(channel, pick.candidate().title()); // linked elsewhere after the choice
        drain("w1");

        assertThat(requestStatus(id)).isEqualTo("RUNNING");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("PENDING");
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(0);
        assertThat(batch(id).get("chosen_candidate_id")).isNull();
        assertThat(port.invalidations.get()).isEqualTo(1);
        assertThat(find(evaluations(id), pick.candidateId()).eligible()).as("first evaluation preserved").isTrue();
        EvaluatedCandidate invalidated = find(rechecks(id), pick.candidateId());
        assertThat(invalidated.eligible()).isFalse();
        assertThat(invalidated.comparisons()).anySatisfy(c -> assertThat(c.reason()).isEqualTo("EXACT_TITLE"));
        assertThat(executor.runOnce("w1")).isFalse();

        EvaluatedCandidate next = DiscoverySelections.ranked(rechecks(id)).get(0);
        // the batch the requester saw before the re-check is stale
        choose(id, next.candidateId(), before.path("version").asInt(), before.path("batchToken").asString(), 409);
        choose(id, pick.candidateId(), 422); // the invalidated candidate can no longer be chosen
        choose(id, next.candidateId(), 200);
        drain("w1");
        assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(linkedTitle(id)).isEqualTo(next.candidate().title());
    }

    @Test
    void aChoiceInvalidatedDuringRetryWaitReturnsToPendingAcrossARestart() throws Exception {
        long channel = channel(List.of());
        long id = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":6");
        drain("w1");
        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(evaluations(id));
        EvaluatedCandidate pick = ranked.get(0);
        choose(id, pick.candidateId(), 200);
        AtomicBoolean once = new AtomicBoolean();
        port.beforeRecord = requestId -> {
            if (once.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated failure after link");
            }
        };
        drain("w1");
        assertThat(step(id, "SELECT").get("status")).isEqualTo("RETRY_WAIT");
        assertThat(step(id, "SELECT").get("attempts")).isEqualTo(1);
        assertThat(step(id, "SELECT").get("retry_at")).isNotNull();
        assertThat(leased(id)).isFalse();

        insertTopic(channel, pick.candidate().title()); // linked elsewhere while the retry waits
        makeDue(id);
        drain("w1");

        Map<String, Object> select = step(id, "SELECT");
        assertThat(select.get("status")).as("waits for the requester, as claim, planner and API expect")
                .isEqualTo("PENDING");
        assertThat(select.get("retry_at")).isNull();
        assertThat(select.get("last_error_code")).isNull();
        assertThat(select.get("attempts")).as("earlier attempts are kept").isEqualTo(1);
        assertThat(batch(id).get("chosen_candidate_id")).isNull();
        assertThat(port.invalidations.get()).isEqualTo(1);
        assertThat(leased(id)).as("the lease is returned while waiting").isFalse();
        assertThat(executor.runOnce("w1")).isFalse();
        try (ProductionExecutor restarted = restartedExecutor()) {
            assertThat(restarted.runOnce("after-restart")).as("the wait survives a restart").isFalse();
            assertThat(discoveryView(id).path("awaitingSelection").asBoolean()).isTrue();

            EvaluatedCandidate next = DiscoverySelections.ranked(rechecks(id)).get(0);
            assertThat(next.candidateId()).isNotEqualTo(pick.candidateId());
            choose(id, next.candidateId(), 200);
            drain(restarted, "after-restart");
            assertThat(requestStatus(id)).isEqualTo("SUCCEEDED");
            assertThat(linkedTitle(id)).isEqualTo(next.candidate().title());
            assertThat(step(id, "SELECT").get("attempts")).as("the new attempt counts on top").isEqualTo(2);
        }
    }

    @Test
    void channelProhibitedTopicsAreBlockedFromTheFrozenSnapshot() throws Exception {
        String prohibited = new FakeDiscoveryProvider().generate(new Input(1, 1, "en", 10, 40, 60, null, null,
                List.of(), 1)).candidates().get(0).coreTopic();
        long channel = channel(List.of(prohibited));
        long id = create(channel, ",\"selectionMode\":\"MANUAL\",\"candidateCount\":10");
        drain("w1");
        List<EvaluatedCandidate> evaluated = evaluations(id);
        assertThat(evaluated).filteredOn(e -> e.candidate().coreTopic().equals(prohibited))
                .allSatisfy(e -> assertThat(e.blockCodes()).contains(Block.CHANNEL_PROHIBITED));
    }

    private static Response withClaims(Response response) {
        List<Candidate> list = response.candidates().stream().map(c -> new Candidate(c.coreTopic(),
                c.centralQuestion(), c.title(), c.angle(), c.outline(), c.evidencePlan(),
                List.of(new Claim("An unverified claim about " + c.coreTopic(), null)), c.suggestedScores(),
                c.rationale())).toList();
        return new Response(response.version(), list);
    }
}
