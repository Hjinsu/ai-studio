package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import com.aistudio.production.contract.MediaAssets;
import com.aistudio.production.contract.MediaEffectPort;
import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionPlanning.ScenePlan;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.budget.BudgetReservationService;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;
import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.InputVersion;
import com.aistudio.production.contract.ProductionPlanning.SceneDraft;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.WorkerExchange;
import com.aistudio.production.contract.ProductionMediaSupplier;
import com.aistudio.production.contract.RealMediaEffectPort;
import com.aistudio.production.discovery.DiscoveryFixtures;
import com.aistudio.production.discovery.DiscoveryRules;
import com.aistudio.production.discovery.RuleBasedDiscoveryEvaluator;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2-03 RESEARCH, SCRIPT and SCENES in the executor against an isolated MariaDB (V1~V12). Requests are created
 * through the production API with the planning pipeline enabled, DISCOVER and SELECT run through the real V2-02
 * ports. Planning uses the real storage and cost adapter. Spies inject provider output or transaction failure
 * without replacing reservation, dispatch, recovery, or receipt application.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false",
        "ai-studio.production.executor.lease-duration=PT10S",
        "logging.level.org.springframework.security.web.csrf.CsrfFilter=DEBUG",
        "spring.web.resources.static-locations=file:../frontend/dist/",
        "ai-studio.production.executor.recovery-recheck=PT1H",
        "ai-studio.production.planning-enabled=true",
        "ai-studio.production.media.enabled=true",
        "ai-studio.production.media.real-dispatch-enabled=true",
        "ai-studio.production.media.fixture-catalog=it-fixture-1",
        "ai-studio.production.media.voice-id=it-voice" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({PlanningExecutionIT.Ports.class, PlanningExecutionIT.FrontendRoutes.class})
class PlanningExecutionIT {
    private static final java.util.concurrent.atomic.AtomicBoolean failMediaWrite = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean mediaProcessReady = new java.util.concurrent.atomic.AtomicBoolean(true);
    private static final AtomicInteger mediaDispatches = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> onMediaSend =
            new java.util.concurrent.atomic.AtomicReference<>(() -> {});
    /** Runs before a local media stage publishes; a test can fail the attempt or stop the JVM-equivalent there. */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MediaStageWork.Context>>
            onLocalWork = new java.util.concurrent.atomic.AtomicReference<>(context -> {});
    private static final String PATH = "/api/v1/production-requests";
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final String APP_USER = "it_planning_" + ProcessHandle.current().pid();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    static {
        DB.provisionProductionAccount(APP_USER, APP_PASSWORD);
    }
    static final String NEUTRON = "How heavy would a teaspoon of neutron star be?";
    static final String TARDIGRADE = "How do tardigrades survive being completely dried out?";
    static final String VENUS = "Why is a day on Venus longer than its year?";
    static final String NO_FIXTURE = "Why are Saturn's rings so thin compared with their width?";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> DB.server().user());
        registry.add("spring.flyway.password", () -> DB.server().password());
        String mediaRoot = isolatedMediaRoot();
        registry.add("ai-studio.production.media.storage-root", () -> mediaRoot);
    }

    private static String isolatedMediaRoot() {
            try {
                return java.nio.file.Files.createTempDirectory("ai-studio-it-media-").toRealPath().toString();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
    }

    @Autowired
    ManualWorkerExchange workerExchange;

    @Test
    void exchangeRejectsStaleLeaseAndAppliesOnlyOnceAfterReclaim() {
        var fixtures = new com.aistudio.content.ContentFixtures(jdbc);
        long channel = fixtures.channel();
        long config = fixtures.config(channel, 1);
        String key = "worker-exchange-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO production_request (channel_id, channel_config_id, requested_by, content_type,
                    pipeline_version, request_key, request_hash, limit_krw, status, lease_owner, lease_epoch,
                    lease_until, created_at)
                VALUES (?, ?, ?, 'SHORTS', 'v2-media-2', ?, SHA2(?, 256), 3000, 'RUNNING', 'first', 1,
                    UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE, UTC_TIMESTAMP(6))
                """, channel, config, fixtures.admin(), key, key);
        long request = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
        jdbc.update("""
                INSERT INTO production_step (production_request_id, stage, status, attempts)
                VALUES (?, 'DISCOVER', 'RUNNING', 1)
                """, request);
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ?", Long.class,
                request);
        var first = new ProductionCostPort.StepOwner(request, step, "first", 1);
        var next = new ProductionCostPort.StepOwner(request, step, "second", 2);
        String hash = com.aistudio.content.command.CanonicalJson.sha256Hex("{}");
        var issued = workerExchange.issue(first, ProductionStage.DISCOVER, "{}");
        assertThat(issued.identity().inputSha256()).isEqualTo(hash);
        assertThat(issued.canonicalInput()).isEqualTo("{}");

        jdbc.update("UPDATE production_request SET lease_owner = 'second', lease_epoch = 2 WHERE id = ?", request);
        assertThat(workerExchange.issue(next, ProductionStage.DISCOVER, "{\"new\":true}")).isEqualTo(issued);
        var draft = new WorkerExchange.Draft(issued.identity(), "{}", hash);
        var review = new WorkerExchange.Review(issued.identity(), hash, "{}", hash);
        assertThatThrownBy(() -> workerExchange.adopt(first, ProductionStage.DISCOVER, draft, review, ignored -> {}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workerExchange.adopt(next, ProductionStage.DISCOVER, draft, review, ignored -> {
            throw new IllegalArgumentException("Reject invalid stage payload");
        })).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE production_step SET attempts = 2 WHERE id = ?", step);
        var replacement = workerExchange.issue(next, ProductionStage.DISCOVER, "{}");
        var newDraft = new WorkerExchange.Draft(replacement.identity(), "{}", hash);
        var newReview = new WorkerExchange.Review(replacement.identity(), hash, "{}", hash);
        assertThatThrownBy(() -> workerExchange.adopt(next, ProductionStage.DISCOVER, newDraft, newReview, ignored -> {
            throw new IllegalArgumentException("Reject invalid stage payload");
        })).isInstanceOf(IllegalArgumentException.class);
        var applied = new AtomicInteger();
        assertThat(workerExchange.adopt(next, ProductionStage.DISCOVER, newDraft, newReview,
                ignored -> applied.incrementAndGet())).isTrue();
        assertThat(workerExchange.adopt(next, ProductionStage.DISCOVER, newDraft, newReview,
                ignored -> applied.incrementAndGet())).isFalse();
        assertThat(applied.get()).isEqualTo(1);
    }

    @Autowired WorkerStageAdoption workerAdoption;
    @Autowired RealMediaEffectPort realEffects;
    @Test
    void reviewedWorkerDiscoveryUsesExistingSelectWithoutCreatingCostEvidence() throws Exception {
        discover(VENUS);
        long request = create("SHORTS", ",\"candidateCount\":5,\"selectionMode\":\"AUTO\"");
        jdbc.update("""
                UPDATE production_request SET pipeline_version = 'v2-media-2', lease_owner = 'manual',
                    lease_epoch = 1, lease_until = UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE, status = 'RUNNING'
                WHERE id = ?
                """, request);
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'DISCOVER'",
                Long.class, request);
        jdbc.update("UPDATE production_step SET status = 'RUNNING', attempts = 1 WHERE id = ?", step);
        var owner = new ProductionCostPort.StepOwner(request, step, "manual", 1);
        var input = discoveryPort.input(request);
        String fixed = PlanningJson.encode(new com.aistudio.production.contract.WorkerDiscoveryRequest(
                input, channel, "Science", "3000", 1, List.of(), List.of("visualPotential"))).json();
        var issued = workerExchange.issue(owner, ProductionStage.DISCOVER, fixed);
        var malformed = PlanningJson.encode(Map.of("version", 1));
        var badDraft = new WorkerExchange.Draft(issued.identity(), malformed.json(), malformed.hash());
        var badReview = new WorkerExchange.Review(issued.identity(), malformed.hash(), malformed.json(), malformed.hash());
        assertThatThrownBy(() -> workerAdoption.adopt(owner, fixed, badDraft, badReview))
                .isInstanceOf(RuntimeException.class);
        var canonical = PlanningJson.encode(discovery.responses.apply(input));
        var draft = new WorkerExchange.Draft(issued.identity(), canonical.json(), canonical.hash());
        var review = new WorkerExchange.Review(issued.identity(), canonical.hash(), canonical.json(), canonical.hash());
        assertThat(workerAdoption.adopt(owner, fixed, draft, review)).isTrue();
        assertThat(workerAdoption.adopt(owner, fixed, draft, review)).isFalse();
        assertThat(jdbc.queryForObject("SELECT worker_exchange_id FROM production_discovery WHERE production_request_id = ?",
                Long.class, request)).isEqualTo(issued.identity().jobId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ?",
                Integer.class, request)).isZero();

        long select = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'SELECT'",
                Long.class, request);
        assertThat(executor.selectWorker(new ProductionCostPort.StepOwner(request, select, "manual", 1))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM production_step WHERE id = ?", String.class, select))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT selection_hash FROM production_request WHERE id = ?", String.class, request))
                .matches("[0-9a-f]{64}");
        var before = port.researchInput(request);
        long research = jdbc.queryForObject(
                "SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'RESEARCH'",
                Long.class, request);
        jdbc.update("UPDATE production_step SET status = 'RUNNING', attempts = 1 WHERE id = ?", research);
        var researchOwner = new ProductionCostPort.StepOwner(request, research, "manual", 1);
        var inputJson = PlanningJson.encode(before);
        var researchJob = workerExchange.issue(researchOwner, ProductionStage.RESEARCH, inputJson.json());
        jdbc.update("UPDATE production_request SET selection_hash = SHA2('changed', 256) WHERE id = ?", request);
        var researchDraft = new WorkerExchange.Draft(researchJob.identity(), canonical.json(), canonical.hash());
        var researchReview = new WorkerExchange.Review(researchJob.identity(), canonical.hash(), canonical.json(), canonical.hash());
        assertThatThrownBy(() -> workerAdoption.adopt(researchOwner, inputJson.json(), researchDraft, researchReview))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE production_request SET selection_hash = ? WHERE id = ?", before.snapshot().selectionHash(), request);
        assertThatThrownBy(() -> jdbc.update("UPDATE production_request SET limit_krw = 3001 WHERE id = ?", request))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        long other = create("SHORTS", ",\"candidateCount\":5,\"selectionMode\":\"AUTO\"");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE production_discovery SET worker_exchange_id = ?, response_json = ?, response_hash = ?,
                    generated_at = UTC_TIMESTAMP(6) WHERE production_request_id = ?
                """, issued.identity().jobId(), canonical.json(), canonical.hash(), other))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE production_discovery SET response_json = ?, response_hash = ?, generated_at = UTC_TIMESTAMP(6)
                WHERE production_request_id = ?
                """, canonical.json(), canonical.hash(), other))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verifyRealSupplierLifecycle(request);
    }

    private void verifyRealSupplierLifecycle(long request) {
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = 'IMAGE'",
                Long.class, request);
        jdbc.update("UPDATE production_step SET status = 'RUNNING', attempts = 1 WHERE id = ?", step);
        var owner = new ProductionCostPort.StepOwner(request, step, "manual", 1);
        var encoded = PlanningJson.encode(Map.of("sku", "fixture-image", "providerNamespace", "fixture-account",
                "priceVersion", "approved-fixture"));
        long first = insertRealMediaItem(request, step, "image:first", encoded);
        long second = insertRealMediaItem(request, step, "image:second", encoded);
        var supplier = org.mockito.Mockito.mock(ProductionMediaSupplier.class);
        org.mockito.Mockito.when(supplier.capability()).thenReturn(new ProductionMediaSupplier.Capability(
                "fixture-account", "fixture-image", ProductionMediaSupplier.Output.IMAGE, true, false, 0, 1280, 1280));
        var job = new ProductionMediaSupplier.JobId("fixture-account", "fixture-job-" + request);
        org.mockito.Mockito.when(supplier.submit(any(), any())).thenReturn(
                new ProductionMediaSupplier.Accepted(job), new ProductionMediaSupplier.Unknown());
        org.mockito.Mockito.when(supplier.poll(job)).thenReturn(new ProductionMediaSupplier.Observation(
                ProductionMediaSupplier.JobState.SUCCEEDED, null));
        org.mockito.Mockito.when(supplier.retrieve(job)).thenReturn(new MediaEffectPort.ProviderResult(
                new MediaEffectPort.ExecutionProof("fixture-request", "fixture-result", BigDecimal.ONE, "KRW",
                        "isolated receipt"), Ports.payloads(ProductionStage.IMAGE)));
        var quote = new ProductionMediaSupplier.Quote("fixture-image", "approved-fixture", "KRW",
                BigDecimal.ONE, java.time.Instant.now().plusSeconds(120));
        var item = new MediaEffectPort.Item(owner, first);
        assertThat(realEffects.executeReal(item, quote, supplier).disposition())
                .isEqualTo(ProductionCostPort.Disposition.WAITING);
        var result = realEffects.recoverReal(item, supplier);
        assertThat(result.disposition()).isEqualTo(ProductionCostPort.Disposition.RECEIPT);
        assertThat(result.receipt().outcome()).isEqualTo(ProductionCostPort.Outcome.RESULT_ADOPTED);
        assertThat(realEffects.recoverReal(item, supplier).receipt()).isEqualTo(result.receipt());
        mediaEffects.apply(item, result.receipt().operationId());

        var unknownItem = new MediaEffectPort.Item(owner, second);
        assertThat(realEffects.executeReal(unknownItem, quote, supplier).disposition())
                .isEqualTo(ProductionCostPort.Disposition.RECOVERY_REQUIRED);
        assertThat(realEffects.executeReal(unknownItem, quote, supplier).disposition())
                .isEqualTo(ProductionCostPort.Disposition.RECOVERY_REQUIRED);
        assertThat(realEffects.recoverReal(unknownItem, supplier).disposition())
                .isEqualTo(ProductionCostPort.Disposition.RECOVERY_REQUIRED);
        verify(supplier, times(2)).submit(any(), any());
        verify(supplier, times(1)).poll(job);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation
                WHERE production_request_id = ? AND status = 'UNKNOWN'
                """, Integer.class, request)).isEqualTo(1);
        long unknown = jdbc.queryForObject("""
                SELECT id FROM provider_operation WHERE production_request_id = ? AND status = 'UNKNOWN'
                """, Long.class, request);
        var recoveredJob = new ProductionMediaSupplier.JobId("fixture-account", "confirmed-" + unknown);
        var evidence = com.aistudio.content.command.CanonicalJson.sha256Hex("independently confirmed fixture job");
        assertThatThrownBy(() -> realEffects.attachKnownJob(unknown, recoveredJob, evidence, Actor.system()))
                .isInstanceOf(com.aistudio.shared.api.ApiException.class);
        realEffects.attachKnownJob(unknown, recoveredJob, evidence, Actor.admin(admin.id()));
        realEffects.attachKnownJob(unknown, recoveredJob, evidence, Actor.admin(admin.id()));
        org.mockito.Mockito.when(supplier.poll(recoveredJob)).thenReturn(new ProductionMediaSupplier.Observation(
                ProductionMediaSupplier.JobState.SUCCEEDED, null));
        org.mockito.Mockito.when(supplier.retrieve(recoveredJob)).thenReturn(new MediaEffectPort.ProviderResult(
                new MediaEffectPort.ExecutionProof("confirmed-request", "confirmed-result", BigDecimal.ONE, "KRW",
                        "confirmed receipt"), Ports.payloads(ProductionStage.IMAGE)));
        var recovered = realEffects.recoverReal(unknownItem, supplier);
        assertThat(recovered.receipt().operationId()).isEqualTo(unknown);
        assertThat(recovered.receipt().outcome()).isEqualTo(ProductionCostPort.Outcome.RESULT_ADOPTED);
        verify(supplier, times(2)).submit(any(), any());
        verify(supplier, times(1)).poll(recoveredJob);
    }

    private long insertRealMediaItem(long request, long step, String target, PlanningJson.Encoded encoded) {
        jdbc.update("""
                INSERT INTO production_media_item (production_request_id, production_step_id, target_key,
                    generation, ordinal, kind, input_json, input_sha256, state, lease_epoch, created_at, updated_at)
                VALUES (?, ?, ?, 1, 1, 'IMAGE', ?, ?, 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, request, step, target, encoded.json(), encoded.hash());
        return jdbc.queryForObject("SELECT id FROM production_media_item WHERE production_step_id = ? AND target_key = ?",
                Long.class, step, target);
    }

    @TestConfiguration
    static class Ports {
        @Bean
        @Primary
        MediaProcessRunner testMediaRunner() {
            return new MediaProcessRunner() {
                @Override
                public boolean ready() { return mediaProcessReady.get(); }

                @Override
                public Path prepare(Scratch scratch) { throw new UnsupportedOperationException(); }

                @Override
                public Result run(Command command, java.util.function.BooleanSupplier cancelled) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        MediaStageWork testMediaWork(MediaEffectPort costs, MediaAssets assets) {
            return new MediaStageWork() {
                @Override
                public Result execute(Context context) {
                    return process(context);
                }

                @Override
                public Result recover(Context context) {
                    return process(context);
                }

                private Result process(Context context) {
                    try {
                        if (context.stage() == ProductionStage.IMAGE || context.stage() == ProductionStage.TTS) {
                            for (var frozen : context.items()) {
                                if ("APPLIED".equals(frozen.state())) {
                                    continue;
                                }
                                var item = new MediaEffectPort.Item(context.owner(), frozen.id());
                                var result = context.recoveryOnly() ? costs.recover(item)
                                        : costs.execute(item, BigDecimal.ONE, id -> {
                                            mediaDispatches.incrementAndGet();
                                            onMediaSend.get().run();
                                            return new MediaEffectPort.ProviderResult(
                                                new MediaEffectPort.ExecutionProof("fixture-" + id, "result-" + id,
                                                        BigDecimal.ONE, "KRW", "Isolated fixture proof"),
                                                payloads(context.stage()));
                                        });
                                if (result.disposition() != ProductionCostPort.Disposition.RECEIPT) {
                                    return new Result(result.disposition() == ProductionCostPort.Disposition.REJECTED
                                            ? Outcome.BLOCKED : Outcome.RECOVERY_REQUIRED, "FIXTURE_EFFECT");
                                }
                                costs.apply(item, result.receipt().operationId());
                                if (result.receipt().outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
                                    return new Result(Outcome.BLOCKED, "FIXTURE_EFFECT");
                                }
                            }
                        } else {
                            onLocalWork.get().accept(context);
                            assets.publish(context, payloads(context.stage()));
                        }
                        return new Result(Outcome.APPLIED, null);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            };
        }

        private static List<MediaEffectPort.Payload> payloads(ProductionStage stage) {
            return switch (stage) {
                case IMAGE -> List.of(payload(Role.SOURCE_IMAGE, "image/png"),
                        payload(Role.NORMALIZED_IMAGE, "image/png"));
                case TTS -> List.of(payload(Role.SOURCE_AUDIO, "audio/wav"), payload(Role.PCM_AUDIO, "audio/wav"),
                        payload(Role.ALIGNMENT, "application/json"));
                case SUBTITLE -> List.of(payload(Role.ASS, "text/plain"), payload(Role.SRT, "text/plain"));
                case RENDER -> List.of(payload(Role.MP4, "video/mp4"));
                case METADATA -> List.of(payload(Role.MEDIA_METADATA, "application/json"));
                case QC -> List.of(payload(Role.QC_REPORT, "application/json"), payload(Role.MANIFEST, "application/json"));
                default -> throw new IllegalArgumentException("Unexpected media stage");
            };
        }

        private static MediaEffectPort.Payload payload(Role role, String type) {
            return new MediaEffectPort.Payload(role, type, 1024, "{}", """
                    {"source":"fixture","provider":"FAKE","license":"test-only","evidenceRef":"fixture-catalog",
                    "commercialUse":true,"publish":true,"redistribution":false,"conditions":"",
                    "verifiedBy":"itest","verifiedAt":"2026-11-27T00:00:00Z"}
                    """, () -> {
                        if (failMediaWrite.get()) {
                            throw new IOException("Fixture lost after the provider response");
                        }
                        return new java.io.ByteArrayInputStream("fixture bytes".getBytes(StandardCharsets.UTF_8));
                    });
        }
        @Bean
        @Primary
        ScriptedDiscoveryProvider scriptedDiscoveryProvider() {
            return new ScriptedDiscoveryProvider();
        }

    }

    @Controller
    @TestComponent
    static class FrontendRoutes {
        @GetMapping({"/production", "/production/requests", "/production/{id}"})
        String index() {
            return "forward:/index.html";
        }
    }

    @org.springframework.beans.factory.annotation.Value("${ai-studio.production.media.storage-root}") String mediaStorageRoot;
    @Autowired com.aistudio.production.media.storage.MediaCleanup mediaCleanup;
    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired BudgetReservationService reservations;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionExecutor executor;
    @MockitoSpyBean PlanningExecutionPort port;
    @MockitoSpyBean PlanningProvider provider;
    @Autowired ScriptedDiscoveryProvider discovery;
    @Autowired JsonMapper json;
    @Autowired ExecutionMapper executionMapper;
    @Autowired BudgetControlLock budgetControl;
    @Autowired LockRetryingTransactions transactions;
    @Autowired ExecutionProperties properties;
    @Autowired ProductionCostPort cost;
    @Autowired MediaEffectPort mediaEffects;
    @Autowired ProductionSelectionPort selection;
    @Autowired com.aistudio.production.contract.DiscoveryExecutionPort discoveryPort;
    @LocalServerPort int serverPort;
    AdminPrincipal admin;
    long channel;

    @BeforeAll
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).startsWith(APP_USER + "@");
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
        discovery.reset();
        mediaDispatches.set(0);
        onLocalWork.set(context -> {});
        onMediaSend.set(() -> {});
        jdbc.update("""
                UPDATE production_request SET status = 'FAILED', lease_owner = NULL, lease_until = NULL
                WHERE status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
                """);
    }

    // ------------------------------------------------------------------ helpers

    /** The topic to select ranks first; the fillers have no offline fixture. */
    void discover(String question) {
        Candidate target = new Candidate(question, question, "Target: " + question,
                "Compare the effect against familiar everyday scales to make it intuitive.",
                List.of("Show a scale diagram", "Draw a comparison chart", "Close with a clear takeaway"),
                List.of(new com.aistudio.production.contract.ProductionDiscovery.EvidencePlan("TEXTBOOK",
                        "Which physical mechanisms explain this result?"),
                        new com.aistudio.production.contract.ProductionDiscovery.EvidencePlan("PEER_REVIEWED_PAPER",
                                "Which measured observations support the explanation?")),
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
        discover(NEUTRON);
        long neutron = create("SHORTS", ",\"candidateCount\":5");
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
            var persisted = jdbc.queryForList("""
                    SELECT r.input_json, r.input_hash, r.body_json, r.body_hash,
                           JSON_VALUE(o.evidence_json, '$.evidence.resultHash') AS receipt_hash
                    FROM production_stage_result r JOIN provider_operation o ON o.id = r.operation_id
                    JOIN production_step s ON s.id = r.step_id WHERE r.request_id = ? ORDER BY s.id
                    """, id);
            assertThat(persisted).hasSize(3);
            for (var item : persisted) {
                assertThat(PlanningJson.matches((String) item.get("input_json"), (String) item.get("input_hash")))
                        .isTrue();
                assertThat(PlanningJson.matches((String) item.get("body_json"), (String) item.get("body_hash")))
                        .isTrue();
                assertThat(item.get("body_hash")).isEqualTo(item.get("receipt_hash"));
            }
            var scriptInput = json.readTree((String) persisted.get(1).get("input_json"));
            var scenesInput = json.readTree((String) persisted.get(2).get("input_json"));
            assertThat(scriptInput.path("researchRef").path("bodyHash").asString())
                    .isEqualTo(persisted.getFirst().get("body_hash"));
            assertThat(scenesInput.path("scriptRef").path("bodyHash").asString())
                    .isEqualTo(persisted.get(1).get("body_hash"));
            assertThat(scriptInput.path("version").path("promptVersion").asString()).isEqualTo("planning-prompt-1");
            assertThat(json.treeToValue(scenesInput.path("version"), InputVersion.class))
                    .isEqualTo(new InputVersion("scenes-prompt-2", "scenes-rules-2", "fake-scenes-2"));
        }
        verify(provider, times(2)).research(any());
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
            assertThat(pair.getValue().visualStyle()).contains("vertical frame").doesNotContain("9:16");
            for (Scene scene : pair.getValue().scenes()) {
                assertThat(scene.imagePrompt()).contains("vertical").doesNotContain("9:16");
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
        injectWeakHook();
        long id = shorts(VENUS);
        drain();
        assertStatus(id, "SUCCEEDED");
        JsonNode script = result(planning(id), ProductionStage.SCRIPT);
        assertThat(codes(script, "BLOCK")).isEmpty();
        assertThat(codes(script, "RECOMMENDATION")).contains("HOOK_TOO_LONG", "HOOK_WEAK");
        assertThat(step(id, ProductionStage.SCENES).get("status")).isEqualTo("SUCCEEDED");
    }

    private void injectWeakHook() {
        doAnswer(call -> {
            Script script = (Script) call.callRealMethod();
            var input = call.<com.aistudio.production.contract.ProductionPlanning.ScriptInput>getArgument(0);
            if (!VENUS.equals(input.research().question())) {
                return script;
            }
            List<Segment> segments = new ArrayList<>(script.segments());
            segments.set(0, new Segment("s1", "This little cube on the kitchen scale looks a lot like sugar.",
                    List.of()));
            return new Script(script.language(), segments, script.hookSegmentIds(),
                    com.aistudio.production.stage.SpeechTiming.estimateMs(segments), script.timingRuleVersion(),
                    script.editorialIntent());
        }).when(provider).writeScript(any());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_STUDIO_REVIEW_EDGE", matches = "true")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void liveEdgeConnectsTheFrontendToTheActualServer() throws Exception {
        jdbc.update("""
                INSERT INTO admin_user (login, password_hash, role, enabled, password_changed_at, created_at, updated_at)
                SELECT 'other-reviewer', password_hash, 'ADMIN', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6) FROM admin_user WHERE id = ?
                """, admin.id());
        discover(NEUTRON);
        var neutron = discovery.responses;
        discover(VENUS);
        var venus = discovery.responses;
        discover(NO_FIXTURE);
        var unsupported = discovery.responses;
        var calls = new AtomicInteger();
        discovery.responses = input -> switch (calls.getAndIncrement()) {
            case 0 -> neutron.apply(input);
            case 1 -> venus.apply(input);
            case 2 -> unsupported.apply(input);
            default -> throw new IllegalStateException("Unexpected discovery call");
        };
        injectWeakHook();
        var command = new ProcessBuilder("node", "e2e/live-production.mjs")
                .directory(Path.of("..", "frontend").toFile()).inheritIO();
        command.environment().put("REVIEW_BROWSER_URL", "http://127.0.0.1:" + serverPort);
        command.environment().put("REVIEW_BROWSER_CHANNEL_ID", Long.toString(channel));
        command.environment().put("REVIEW_BROWSER_PASSWORD", ApiTestSupport.PASSWORD);
        Process browser = command.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(110);
            while (browser.isAlive() && System.nanoTime() < deadline) {
                drain();
                browser.waitFor(200, TimeUnit.MILLISECONDS);
            }
            assertThat(browser.isAlive()).as("Edge validation finished").isFalse();
            assertThat(browser.exitValue()).as("Edge validation exit status").isZero();
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            if (browser.isAlive()) {
                browser.descendants().forEach(ProcessHandle::destroy);
                browser.destroy();
            }
        }
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
        verify(provider, never()).writeScript(any());
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
        doAnswer(call -> {
            Research r = (Research) call.callRealMethod();
            List<Source> sources = r.sources().stream().map(s -> new Source(s.id(), s.origin(), s.status(),
                    "https://fixtures.ai-studio.invalid/made-up/" + s.id().replace(':', '-'), s.title(),
                    s.publisher(), s.checkedAtUtc(), s.documentHash())).toList();
            return new Research(r.question(), r.angle(), r.investigations(), r.claims(), sources, r.limitations(),
                    r.scriptClaimIds());
        }).when(provider).research(any());
        long id = shorts(NEUTRON);
        drain();
        assertStatus(id, "FAILED");
        assertThat(codes(result(planning(id), ProductionStage.RESEARCH), "BLOCK")).contains("SOURCE_NOT_FOUND");
        assertThat(step(id, ProductionStage.SCRIPT).get("status")).isEqualTo("PENDING");
    }

    @Test
    void hypothesisStatedAsFactBlocksScriptAndScenesNeverRun() throws Exception {
        doAnswer(call -> {
            Script s = (Script) call.callRealMethod();
            List<Segment> segments = new ArrayList<>(s.segments());
            segments.replaceAll(seg -> seg.id().equals("s6")
                    ? new Segment("s6", "Deeper inside, the matter breaks down into free quarks.", seg.claimIds())
                    : seg);
            return new Script(s.language(), segments, s.hookSegmentIds(),
                    com.aistudio.production.stage.SpeechTiming.estimateMs(segments), s.timingRuleVersion(),
                    s.editorialIntent());
        }).when(provider).writeScript(any());
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
        doAnswer(call -> {
            ScenePlan s = (ScenePlan) call.callRealMethod();
            return new ScenePlan(s.visualStyle(), s.scenes().subList(0, s.scenes().size() - 1));
        }).when(provider).planScenes(any());
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
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (!failed[0]) {
                failed[0] = true;
                throw new IllegalStateException("simulated failure inside the fenced apply");
            }
            return result;
        }).when(AopTestUtils.<PlanningExecutionPort>getUltimateTargetObject(port)).apply(any(), any(), any());
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
        verify(provider).research(any());
        assertThat(((Number) step(id, ProductionStage.RESEARCH).get("attempts")).intValue()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void legacyScenesReceiptReplaysFromFixedInputWithoutRegeneration(boolean unsupportedNumbers) throws Exception {
        var legacy = new InputVersion("planning-prompt-1", "planning-rules-1", "fake-planning-1");
        var adapter = AopTestUtils.<PlanningExecutionPort>getUltimateTargetObject(port);
        boolean[] createLegacyInput = { true };
        doAnswer(call -> {
            ScenesInput input = (ScenesInput) call.callRealMethod();
            return createLegacyInput[0] ? new ScenesInput(input.snapshot(), legacy, input.scriptRef(), input.script())
                    : input;
        }).when(adapter).scenesInput(anyLong());
        doAnswer(call -> {
            ScenePlan plan = (ScenePlan) call.callRealMethod();
            if (!unsupportedNumbers) {
                return plan;
            }
            List<SceneDraft> drafts = new ArrayList<>(plan.scenes());
            SceneDraft first = drafts.getFirst();
            drafts.set(0, new SceneDraft(first.id(), first.order(), first.segmentIds(), first.estimatedDurationMs(),
                    first.visualDescription(), first.imagePrompt() + ", vertical 9:16", first.direction(),
                    first.transition()));
            return new ScenePlan(plan.visualStyle(), drafts);
        }).when(provider).planScenes(any());
        // Settle with the original input and body, then simulate a restart before the receipt could be applied.
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (createLegacyInput[0] && operations(call.<ProductionCostPort.StepOwner>getArgument(0).requestId(),
                    ProductionStage.SCENES) != 0) {
                throw new IllegalStateException("simulated restart before legacy scene receipt application");
            }
            return result;
        }).when(adapter).apply(any(), any(), any());

        long id = shorts(VENUS);
        drain();
        assertStatus(id, "RECOVERY_REQUIRED");
        assertThat(step(id, ProductionStage.SCENES).get("last_error_code")).isEqualTo("RECEIPT_APPLY_FAILED");
        String fixedEffect = """
                SELECT r.operation_id, r.input_json, r.input_hash, r.body_json, r.body_hash,
                       o.request_hash, o.evidence_json, o.evidence_hash, o.receipt_outcome, o.status, o.reservation_id
                FROM production_stage_result r JOIN provider_operation o ON o.id = r.operation_id
                JOIN production_step s ON s.id = r.step_id WHERE r.request_id = ? AND s.stage = 'SCENES'
                """;
        Map<String, Object> before = jdbc.queryForMap(fixedEffect, id);
        assertThat(before).containsEntry("status", "SETTLED").containsEntry("receipt_outcome", "RESULT_ADOPTED");
        assertThat(PlanningJson.matches((String) before.get("input_json"), (String) before.get("input_hash"))).isTrue();
        assertThat(PlanningJson.matches((String) before.get("body_json"), (String) before.get("body_hash"))).isTrue();

        createLegacyInput[0] = false;
        assertThat(port.scenesInput(id).version()).isEqualTo(legacy);
        makeDue(id);
        drain();

        assertThat(jdbc.queryForMap(fixedEffect, id)).isEqualTo(before);
        assertThat(operations(id, ProductionStage.SCENES)).isEqualTo(1);
        verify(provider).planScenes(any());
        assertThat(((Number) step(id, ProductionStage.SCENES).get("attempts")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT applied_at IS NOT NULL FROM provider_operation WHERE id = ?",
                Boolean.class, before.get("operation_id"))).isTrue();
        var result = result(planning(id), ProductionStage.SCENES);
        if (unsupportedNumbers) {
            assertStatus(id, "FAILED");
            assertThat(codes(result, "BLOCK")).containsExactly("SCENE_NEW_FACT");
            assertThat(step(id, ProductionStage.IMAGE).get("status")).isEqualTo("PENDING");
        } else {
            assertStatus(id, "SUCCEEDED");
            assertThat(codes(result, "BLOCK")).isEmpty();
        }
    }

    @Test
    void staleLeaseCannotApplyAPlanningReceipt() throws Exception {
        long id = shorts(VENUS);
        boolean[] stolen = { false };
        doAnswer(call -> {
            if (!stolen[0]) {
                stolen[0] = true; // another worker takes over after the dispatch
                jdbc.update("UPDATE production_request SET lease_epoch = lease_epoch + 1 WHERE id = ?", id);
            }
            return call.callRealMethod();
        }).when(port).response(any(), any());
        assertThat(executor.runOnce("stale-worker")).isTrue();
        assertThat(step(id, ProductionStage.RESEARCH).get("status")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND applied_at IS NULL
                """, Integer.class, id)).isEqualTo(1);
        jdbc.update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isEqualTo(1);
        verify(provider).research(any());
    }

    @Test
    void cancelAfterResearchStopsFurtherPlanningDispatch() throws Exception {
        long id = shorts(TARDIGRADE);
        doAnswer(call -> {
            jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ? AND "
                    + "cancel_requested_at IS NULL", id);
            return call.callRealMethod();
        }).when(AopTestUtils.<PlanningExecutionPort>getUltimateTargetObject(port)).apply(any(), any(), any());
        drain();
        assertStatus(id, "CANCELLED");
        assertThat(operations(id, ProductionStage.SCRIPT)).isZero();
        verify(provider, never()).writeScript(any());
    }

    @Test
    void channelEditsAfterSubmissionDoNotChangeThePlanningSnapshot() throws Exception {
        long id = shorts(VENUS);
        Actor actor = Actor.admin(admin.id());
        channels.update(channel, channels.get(channel).channel().version(), null, "ko", null, actor);
        channels.createConfigRevision(channel, 1, new ChannelSettings("SHORTS", 50, 58, 480, 720,
                "Changed domain", null, "Changed voice", "Changed framing", true, null), "Snapshot check", actor);
        drain();
        assertStatus(id, "SUCCEEDED");
        var snapshots = jdbc.queryForList("""
                SELECT JSON_QUERY(input_json, '$.snapshot') FROM production_stage_result WHERE request_id = ?
                """, String.class, id);
        assertThat(snapshots).hasSize(3).containsOnly(snapshots.getFirst());
        var frozen = json.readTree(snapshots.getFirst());
        assertThat(frozen.path("language").asString()).isEqualTo("en");
        assertThat(frozen.path("narrationStyle").asString()).isEqualTo("Energetic and clear");
        assertThat(frozen.path("visualStyle").asString()).isEqualTo("Clean infographic");
        assertThat(frozen.path("revisionId").asLong()).isPositive();
    }

    @Test
    void oversizedReviewReplaysTheSettledReceiptWithOneBoundedBlockingRecord() throws Exception {
        long id = shorts(NEUTRON);
        doAnswer(call -> {
            var input = call.getArgument(0, com.aistudio.production.contract.ProductionPlanning.ResearchInput.class);
            var claims = IntStream.range(0, 1_000).mapToObj(i ->
                    new Claim("c" + i, ClaimKind.FACT, "Unconfirmed claim", List.of(), null)).toList();
            return new Research(input.question(), input.angle(), List.of(), claims, List.of(), List.of("Unconfirmed"),
                    List.of());
        }).when(provider).research(any());
        var applyAttempts = new AtomicInteger();
        var records = new ArrayList<String>();
        var epochs = new ArrayList<Long>();
        doAnswer(call -> {
            var owner = call.getArgument(0, ProductionCostPort.StepOwner.class);
            Object result = call.callRealMethod();
            epochs.add(owner.leaseEpoch());
            records.add(jdbc.queryForObject("SELECT validation_json FROM production_stage_result WHERE request_id = ?",
                    String.class, id));
            if (applyAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("interrupt after storing validation");
            }
            return result;
        }).when(AopTestUtils.<PlanningExecutionPort>getUltimateTargetObject(port)).apply(any(), any(), any());
        drain();
        assertStatus(id, "RECOVERY_REQUIRED");
        assertThat(step(id, ProductionStage.RESEARCH)).containsEntry("last_error_code", "RECEIPT_APPLY_FAILED");
        var settled = researchSettlement(id);
        assertThat(settled).containsEntry("status", "SETTLED").containsEntry("applied_at", null);
        assertThat(jdbc.queryForObject("SELECT validation_json FROM production_stage_result WHERE request_id = ?",
                String.class, id)).isNull();
        makeDue(id);
        assertThat(executor.runOnce("new-review-lease")).isTrue();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.RESEARCH)).containsEntry("last_error_code", "PLANNING_VALIDATION_BLOCKED");
        assertThat(researchSettlement(id)).containsEntry("receipt_event_id", settled.get("receipt_event_id"))
                .doesNotContainEntry("applied_at", null);
        assertThat(epochs).hasSize(2);
        assertThat(epochs.getLast()).isGreaterThan(epochs.getFirst());
        assertThat(records).hasSize(2).containsOnly(records.getFirst());
        assertThat(records.getFirst().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(128 * 1024);
        assertThat(records.getFirst()).contains("CORE_EVIDENCE_INSUFFICIENT", "VALIDATION_FINDINGS_OMITTED",
                "938 findings omitted");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cost_log l JOIN provider_operation o ON o.reservation_id = l.reservation_id
                JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = 'RESEARCH' AND l.entry_type = 'CONFIRM'
                """, Integer.class, id)).isEqualTo(1);
        assertThat(operations(id, ProductionStage.RESEARCH)).isEqualTo(1);
        assertThat(operations(id, ProductionStage.SCRIPT)).isZero();
        verify(provider).research(any());
        verify(provider, never()).writeScript(any());
    }

    private Map<String, Object> researchSettlement(long id) {
        return jdbc.queryForMap("""
                SELECT o.status, o.receipt_event_id, o.applied_at FROM provider_operation o
                JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = 'RESEARCH'
                """, id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"segments", "description", "structure", "null-response", "timeout"})
    void separatesReturnedInvalidScenesFromResponseTimeout(String failure) throws Exception {
        long id = shorts(NEUTRON);
        doAnswer(call -> switch (failure) {
            case "segments" -> new ScenePlan("diagram", List.of(new SceneDraft("v1", 1, null, 45_000,
                    "Star", "Star", "Static", null)));
            case "description" -> new ScenePlan("diagram", List.of(new SceneDraft("v1", 1, List.of("s1"), 45_000,
                    null, "Star", "Static", null)));
            case "structure" -> invalidSceneStructure();
            case "null-response" -> null;
            case "timeout" -> scenesResponseTimeout();
            default -> throw new AssertionError(failure);
        }).when(provider).planScenes(any());
        drain();
        boolean unknown = failure.equals("timeout");
        assertStatus(id, unknown ? "RECOVERY_REQUIRED" : "FAILED");
        if (unknown) {
            makeDue(id);
            assertThat(executor.runOnce("scene-timeout-recovery")).isTrue();
            assertStatus(id, "RECOVERY_REQUIRED");
        } else {
            assertThat(step(id, ProductionStage.SCENES)).containsEntry("last_error_code", "EFFECT_EXECUTED_FAILED");
            assertThat(executor.runOnce("scene-format-recheck")).isFalse();
        }
        var operation = jdbc.queryForMap("""
                SELECT o.status, o.receipt_outcome, o.receipt_event_id, o.applied_at, r.body_json
                FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                JOIN production_stage_result r ON r.operation_id = o.id
                WHERE o.production_request_id = ? AND s.stage = 'SCENES'
                """, id);
        assertThat(operation).containsEntry("status", unknown ? "UNKNOWN" : "SETTLED")
                .containsEntry("receipt_outcome", unknown ? null : "EXECUTED_FAILED")
                .containsEntry("body_json", null);
        if (unknown) {
            assertThat(operation).containsEntry("receipt_event_id", null).containsEntry("applied_at", null);
        } else {
            assertThat(operation.get("receipt_event_id")).isNotNull();
            assertThat(operation.get("applied_at")).isNotNull();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cost_log l JOIN provider_operation o ON o.reservation_id = l.reservation_id
                JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = 'SCENES' AND l.entry_type = 'CONFIRM'
                """, Integer.class, id)).isEqualTo(unknown ? 0 : 1);
        assertThat(operations(id, ProductionStage.IMAGE)).isZero();
        verify(provider).planScenes(any());
    }

    private static ScenePlan scenesResponseTimeout() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (var stub = new ServerSocket(0, 1, loopback);
                var client = new Socket(loopback, stub.getLocalPort());
                var silentPeer = stub.accept()) {
            silentPeer.getOutputStream().flush();
            client.setSoTimeout(100);
            throw new AssertionError("Expected timeout, got " + client.getInputStream().read());
        } catch (SocketTimeoutException timeout) {
            throw new UncheckedIOException(timeout);
        } catch (IOException failure) {
            throw new AssertionError("Could not test local response timeout", failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ScenePlan invalidSceneStructure() {
        List rawDrafts = List.of("untyped draft");
        return new ScenePlan("diagram", rawDrafts);
    }

    @Test
    void mediaCleanupPreservesLeasedAndRecentlyWrittenScratch(
            @org.junit.jupiter.api.io.TempDir Path scratch) throws Exception {
        long leased = create("SHORTS", "");
        long idle = create("SHORTS", "");
        jdbc.update("""
                UPDATE production_request SET lease_owner = 'cleanup-test', lease_epoch = 1,
                    lease_until = UTC_TIMESTAMP(6) + INTERVAL 10 MINUTE WHERE id = ?
                """, leased);
        var retainedForLease = scratchAttempt(scratch, leased, 1, false);
        var expired = scratchAttempt(scratch, idle, 1, false);
        var retainedForRecentWrite = scratchAttempt(scratch, idle, 2, true);
        var retainedForLocalWriter = scratchAttempt(scratch, idle, 3, false);
        var retainedForUnknownOwner = scratchAttempt(scratch, Long.MAX_VALUE, 1, false);

        mediaCleanup.reap(scratch.toRealPath(), Path.of(mediaStorageRoot), retainedForLocalWriter::equals);

        assertThat(retainedForLease).exists();
        assertThat(expired).doesNotExist();
        assertThat(retainedForRecentWrite).exists();
        assertThat(retainedForLocalWriter).exists();
        assertThat(retainedForUnknownOwner).exists();
    }

    private static Path scratchAttempt(Path root, long request, int attempt, boolean recent) throws IOException {
        var dir = java.nio.file.Files.createDirectory(root.resolve("r" + request + "-i1-a" + attempt));
        var payload = java.nio.file.Files.writeString(dir.resolve("output"), "isolated");
        var past = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(25)));
        if (!recent) {
            java.nio.file.Files.setLastModifiedTime(payload, past);
        }
        java.nio.file.Files.setLastModifiedTime(dir, past);
        return dir;
    }

    @Test
    void scratchRetirementRequiresDurableAssetsAndToleratesDeletionFailure(
            @org.junit.jupiter.api.io.TempDir Path scratch) throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        drain();
        assertStatus(id, "SUCCEEDED");
        long render = jdbc.queryForObject("""
                SELECT id FROM production_media_item WHERE production_request_id = ? AND kind = 'RENDER'
                """, Long.class, id);
        String relative = jdbc.queryForObject("""
                SELECT relative_path FROM production_media_asset WHERE item_id = ? AND role = 'MP4'
                """, String.class, render);
        var handle = new com.aistudio.production.contract.MediaProcessRunner.Scratch(id, render, 1);
        var storage = Path.of(mediaStorageRoot);
        var workspaceVideo = storage.resolve(relative);
        var dir = java.nio.file.Files.createDirectory(scratch.resolve("r" + id + "-i" + render + "-a1"));
        var output = java.nio.file.Files.writeString(dir.resolve("output"), "temporary");

        var bytes = java.nio.file.Files.readAllBytes(workspaceVideo);
        var corrupt = bytes.clone();
        corrupt[0] ^= 1;
        java.nio.file.Files.write(workspaceVideo, corrupt);
        assertThat(mediaCleanup.retireApplied(handle, dir, storage)).isFalse();
        assertThat(output).exists();
        java.nio.file.Files.write(workspaceVideo, bytes);

        if (com.sun.jna.Platform.isWindows()) {
            try (var lock = java.nio.channels.FileChannel.open(output, java.nio.file.StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                assertThat(mediaCleanup.retireApplied(handle, dir, storage)).isTrue();
                assertThat(output).hasContent("temporary");
            }
        }
        assertThat(mediaCleanup.retireApplied(handle, dir, storage)).isTrue();
        assertThat(dir).doesNotExist();
        assertThat(java.nio.file.Files.readAllBytes(workspaceVideo)).isEqualTo(bytes);
    }

    @Test
    void expiredMediaWriterCannotPublishAndReplacementSettlesAfterCancel(
            @org.junit.jupiter.api.io.TempDir Path scratch) throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        onMediaSend.set(() -> jdbc.update(
                "UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id));
        try {
            assertThat(executor.runOnce("expired-writer")).isTrue();
        } finally {
            onMediaSend.set(() -> {});
        }
        long epoch = jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id);
        var state = jdbc.queryForMap("SELECT id, operation_id, state FROM production_media_item "
                + "WHERE production_request_id = ? AND state = 'PROVEN'", id);
        Path retained = java.nio.file.Files.createDirectory(scratch.resolve("r" + id + "-i" + state.get("id") + "-a1"));
        var past = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(25)));
        java.nio.file.Files.setLastModifiedTime(retained, past);
        mediaCleanup.reap(scratch.toRealPath(), Path.of(mediaStorageRoot), path -> false);
        assertThat(retained).exists();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_media_asset WHERE item_id = ?", Integer.class,
                state.get("id"))).isZero();
        jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
        makeDue(id);
        drain();
        assertStatus(id, "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id))
                .isGreaterThan(epoch);
        assertThat(mediaDispatches).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE event_key = CONCAT('production:settle:', ?)",
                Integer.class, state.get("operation_id"))).isEqualTo(1);
        mediaCleanup.reap(scratch.toRealPath(), Path.of(mediaStorageRoot), path -> false);
        assertThat(retained).doesNotExist();
    }

    @Test
    void movedMediaReplaysAfterFinalizeRollbackWithoutResendingOrSettlingTwice() throws Exception {
        discover(VENUS);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        var root = new JdbcTemplate(DB.dataSource());
        root.execute("""
                CREATE TRIGGER it_media_abort_finalize BEFORE UPDATE ON production_media_item FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.state = 'FINALIZED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT interruption after file publication';
                    END IF;
                END
                """.formatted(id));
        long operation;
        try {
            drain();
            assertStatus(id, "RECOVERY_REQUIRED");
            assertThat(mediaDispatches).hasValue(1);
            long itemId = jdbc.queryForObject("SELECT id FROM production_media_item WHERE production_request_id = ?"
                    + " AND state = 'PROVEN'", Long.class, id);
            operation = jdbc.queryForObject("SELECT operation_id FROM production_media_item WHERE id = ?", Long.class,
                    itemId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM production_media_asset WHERE item_id = ?"
                    + " AND state = 'STAGED'", Integer.class, itemId)).isEqualTo(2);
            for (String path : jdbc.queryForList("SELECT relative_path FROM production_media_asset WHERE item_id = ?",
                    String.class, itemId)) {
                assertThat(java.nio.file.Files.isRegularFile(Path.of(mediaStorageRoot).resolve(path))).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT receipt_event_id FROM provider_operation WHERE id = ?", Long.class,
                    operation)).isNull();
        } finally {
            root.execute("DROP TRIGGER it_media_abort_finalize");
        }
        makeDue(id);
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(mediaDispatches).hasValue(jdbc.queryForObject("SELECT COUNT(*) FROM production_media_item "
                + "WHERE production_request_id = ? AND operation_id IS NOT NULL", Integer.class, id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE event_key = CONCAT('production:settle:', ?)",
                Integer.class, operation)).isEqualTo(1);
    }

    @Test
    void persistedMediaProofSettlesOnceWhenFileWriteFailsAndCancellationRecovers() throws Exception {
        discover(VENUS);
        failMediaWrite.set(true);
        try {
            long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
            drain();
            assertStatus(id, "RECOVERY_REQUIRED");
            var proof = jdbc.queryForMap("""
                    SELECT i.operation_id, i.execution_proof_sha256, o.receipt_event_id
                    FROM production_media_item i JOIN provider_operation o ON o.id = i.operation_id
                    WHERE i.production_request_id = ? AND i.kind = 'IMAGE'
                    """, id);
            assertThat(proof.get("execution_proof_sha256")).isNotNull();
            assertThat(proof.get("receipt_event_id")).isNull();
            long operationId = ((Number) proof.get("operation_id")).longValue();

            // Simulate application restart with render preparation unavailable during cancel/recovery.
            mediaProcessReady.set(false);
            jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
            makeDue(id);
            drain();
            assertStatus(id, "CANCELLED");
            assertThat(mediaDispatches).hasValue(1);
            assertThat(jdbc.queryForMap("""
                    SELECT receipt_outcome, applied_at IS NOT NULL AS applied FROM provider_operation WHERE id = ?
                    """, operationId)).containsEntry("receipt_outcome", "EXECUTED_FAILED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM provider_operation WHERE id = ? AND applied_at IS NOT NULL
                    """, Integer.class, operationId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM cost_log WHERE event_key = CONCAT('production:settle:', ?)
                    """, Integer.class, operationId)).isEqualTo(1);
        } finally {
            failMediaWrite.set(false);
            mediaProcessReady.set(true);
        }
    }

    @Test
    void optedInMediaFreezesCharacterlessProfileAndStreamsFinalAsset() throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        var profile = jdbc.queryForMap("""
                SELECT input_json, input_sha256 FROM production_media_item
                WHERE production_request_id = ? AND kind = 'PROFILE'
                """, id);
        String snapshot = profile.get("input_json").toString();
        assertThat(PlanningJson.matches(snapshot, profile.get("input_sha256").toString())).isTrue();
        assertThat(json.readTree(snapshot).get("characterProfiles").size()).isZero();
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_item
                WHERE production_request_id = ? AND kind IN ('IMAGE', 'TTS')
                  AND operation_id IS NOT NULL AND execution_proof_sha256 IS NOT NULL AND state = 'APPLIED'
                """, Integer.class, id)).isGreaterThan(2);
        long video = jdbc.queryForObject("""
                SELECT a.id FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role = 'MP4' AND a.state = 'FINAL'
                """, Long.class, id);
        String path = PATH + "/" + id + "/media/" + video;
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=0-6"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Range", "bytes 0-6/13"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("fixture"));
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=99-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(path)
                        .with(ApiTestSupport.as(admin)).header("Range", "bytes=0-2"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .longValue("Content-Length", 13));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=-5"))
                .andExpect(status().isPartialContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("bytes"));
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=1-3", "bytes=4-6"))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=0-1").header("If-Range", "\"old\""))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=9999999999999999999999999999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        long unrelated = create("SHORTS", "");
        mvc.perform(get(PATH + "/" + unrelated + "/media/" + video).with(ApiTestSupport.as(admin)))
                .andExpect(status().isNotFound());
        String stored = jdbc.queryForObject("SELECT relative_path FROM production_media_asset WHERE id = ?", String.class,
                video);
        java.nio.file.Files.writeString(Path.of(mediaStorageRoot).resolve(stored), "altered bytes");
        mvc.perform(get(path).with(ApiTestSupport.as(admin)).header("Range", "bytes=0-1"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ V2-04 B01/B02 stop-and-restart recovery

    /** Test stand-in for a JVM exit: an Error escapes every executor catch, so only committed state remains. */
    static final class SimulatedStop extends Error {
        SimulatedStop() {
            super("simulated JVM stop", null, false, false);
        }
    }

    JdbcTemplate root() {
        return new JdbcTemplate(DB.dataSource());
    }

    /**
     * B01: the shared permit transaction is aborted for the first operation of {@code stage}, so only the reservation
     * and the item's operation binding are committed. The resulting park is then rewritten to the state a JVM exit
     * leaves (step RUNNING, lease of the stopped worker expired), which the planner reaches the same way.
     */
    long[] stopAfterMediaReservation(long id, ProductionStage stage) {
        var root = root();
        root.execute("""
                CREATE TRIGGER it_b01_stop_before_dispatch BEFORE UPDATE ON provider_operation FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.status = 'DISPATCHED' AND OLD.status = 'RESERVED'
                       AND (SELECT stage FROM production_step WHERE id = NEW.production_step_id) = '%s' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT stop between reservation and dispatch';
                    END IF;
                END
                """.formatted(id, stage.name()));
        try {
            drain();
        } finally {
            root.execute("DROP TRIGGER it_b01_stop_before_dispatch");
        }
        var bound = jdbc.queryForMap("""
                SELECT i.id AS item_id, i.state, o.id AS operation_id, o.effect_key, o.status, o.dispatched_at,
                       s.id AS step_id, r.status AS reservation_status
                FROM production_media_item i JOIN provider_operation o ON o.id = i.operation_id
                JOIN production_step s ON s.id = i.production_step_id
                JOIN budget_reservation r ON r.id = o.reservation_id
                WHERE i.production_request_id = ? AND i.kind = ?
                """, id, stage.name());
        assertThat(bound).containsEntry("state", "ACTIVE").containsEntry("status", "RESERVED")
                .containsEntry("reservation_status", "ACTIVE");
        assertThat(bound.get("dispatched_at")).isNull();
        assertThat(mediaDispatches).hasValue(stage == ProductionStage.IMAGE ? 0 : jdbc.queryForObject(
                "SELECT COUNT(*) FROM production_media_item WHERE production_request_id = ? AND kind = 'IMAGE'",
                Integer.class, id));
        root.update("""
                UPDATE production_step SET status = 'RUNNING', retry_at = NULL, last_error_code = NULL
                WHERE production_request_id = ? AND stage = ?
                """, id, stage.name());
        root.update("""
                UPDATE production_request SET status = 'RUNNING', lease_owner = 'stopped-worker',
                    lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?
                """, id);
        long epoch = jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id);
        return new long[] { ((Number) bound.get("item_id")).longValue(), ((Number) bound.get("operation_id")).longValue(),
                ((Number) bound.get("step_id")).longValue(), epoch };
    }

    int settlements(long operation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE event_key = CONCAT('production:settle:', ?)",
                Integer.class, operation);
    }

    void assertClosedWithoutDispatch(long operation) {
        assertThat(jdbc.queryForMap("""
                SELECT o.status, o.receipt_outcome, IF(o.dispatched_at IS NULL, 'yes', 'no') AS never_sent,
                       IF(o.applied_at IS NOT NULL, 'yes', 'no') AS applied, r.status AS reservation_status,
                       IF(r.reserved_remaining_krw = 0 AND r.recognized_cost_krw = 0, 'yes', 'no') AS returned
                FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id WHERE o.id = ?
                """, operation)).containsEntry("status", "SETTLED").containsEntry("receipt_outcome", "NOT_EXECUTED")
                .containsEntry("never_sent", "yes").containsEntry("applied", "yes")
                .containsEntry("reservation_status", "RELEASED").containsEntry("returned", "yes");
        assertThat(settlements(operation)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "IMAGE", "TTS" })
    void mediaReservationCommittedBeforeDispatchResumesOnTheSameOperationAfterRestart(String kind) throws Exception {
        ProductionStage stage = ProductionStage.valueOf(kind);
        discover(VENUS);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        long[] stopped = stopAfterMediaReservation(id, stage);
        String key = jdbc.queryForObject("SELECT effect_key FROM provider_operation WHERE id = ?", String.class, stopped[1]);
        int before = mediaDispatches.get();

        // The stopped worker's expired lease cannot dispatch the reserved operation.
        var stale = new ProductionCostPort.StepOwner(id, stopped[2], "stopped-worker", stopped[3]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaEffects.execute(
                new MediaEffectPort.Item(stale, stopped[0]), BigDecimal.ONE, operation -> {
                    throw new AssertionError("a stale worker must not reach the provider");
                })).isInstanceOf(com.aistudio.shared.api.ApiException.class);
        assertThat(jdbc.queryForObject("SELECT dispatched_at FROM provider_operation WHERE id = ?", Object.class,
                stopped[1])).isNull();

        drain(); // a new worker with a new lease epoch
        assertStatus(id, "SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id))
                .isGreaterThan(stopped[3]);
        var resumed = jdbc.queryForMap("""
                SELECT i.operation_id, o.effect_key, o.receipt_outcome, i.state FROM production_media_item i
                JOIN provider_operation o ON o.id = i.operation_id WHERE i.id = ?
                """, stopped[0]);
        assertThat(((Number) resumed.get("operation_id")).longValue()).isEqualTo(stopped[1]);
        assertThat(resumed).containsEntry("effect_key", key).containsEntry("receipt_outcome", "RESULT_ADOPTED")
                .containsEntry("state", "APPLIED");
        int items = jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_item WHERE production_request_id = ? AND kind IN ('IMAGE', 'TTS')
                """, Integer.class, id);
        assertThat(operations(id, ProductionStage.IMAGE) + operations(id, ProductionStage.TTS)).isEqualTo(items);
        assertThat(mediaDispatches).as("each effect sent exactly once, the resumed one after the restart")
                .hasValue(items).hasValueGreaterThan(before);
        assertThat(settlements(stopped[1])).isEqualTo(1);
        // the stale worker still cannot act after the recovery
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaEffects.execute(
                new MediaEffectPort.Item(stale, stopped[0]), BigDecimal.ONE, operation -> {
                    throw new AssertionError("a stale worker must not reach the provider");
                })).isInstanceOf(com.aistudio.shared.api.ApiException.class);
        assertThat(settlements(stopped[1])).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void undispatchedMediaReservationIsClosedWithoutDispatchWhenItsBudgetPeriodEnds(boolean mediaReady)
            throws Exception {
        // A channel DAY policy of this test's channel only, so ending its period leaves shared buckets unchanged.
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("1000"), "UTC", null, Actor.admin(admin.id()));
        discover(VENUS);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        long[] stopped = stopAfterMediaReservation(id, ProductionStage.IMAGE);
        assertThat(root().update("""
                UPDATE budget_bucket b JOIN budget_reservation_bucket rb ON rb.bucket_id = b.id
                JOIN budget_policy p ON p.id = b.policy_id
                SET b.period_end = b.period_start + INTERVAL 1 MICROSECOND
                WHERE rb.reservation_id = (SELECT reservation_id FROM provider_operation WHERE id = ?)
                  AND p.scope = 'CHANNEL' AND p.channel_id = ?
                """, stopped[1], channel)).isEqualTo(1);
        mediaProcessReady.set(mediaReady); // false: the recovery-only path presents the item without a payload
        try {
            drain();
        } finally {
            mediaProcessReady.set(true);
        }
        assertStatus(id, "FAILED");
        assertThat(mediaDispatches).hasValue(0);
        assertClosedWithoutDispatch(stopped[1]);
        assertThat(jdbc.queryForObject("SELECT state FROM production_media_item WHERE id = ?", String.class, stopped[0]))
                .isEqualTo("FAILED");
        assertThat(operations(id, ProductionStage.IMAGE)).isEqualTo(1);
    }

    @Test
    void undispatchedMediaReservationIsClosedWithoutDispatchWhenTheRequestIsCancelled() throws Exception {
        discover(VENUS);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        long[] stopped = stopAfterMediaReservation(id, ProductionStage.TTS);
        int sent = mediaDispatches.get();
        jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
        drain();
        assertStatus(id, "CANCELLED");
        assertThat(mediaDispatches).hasValue(sent);
        assertClosedWithoutDispatch(stopped[1]);
        assertThat(jdbc.queryForObject("SELECT state FROM production_media_item WHERE id = ?", String.class, stopped[0]))
                .isEqualTo("CANCELLED");
    }

    @Test
    void dispatchedMediaOperationWithoutEvidenceIsNeverResentAfterRestart() throws Exception {
        discover(VENUS);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        onMediaSend.set(() -> { throw new IllegalStateException("simulated response loss after dispatch"); });
        drain();
        onMediaSend.set(() -> {});
        assertStatus(id, "RECOVERY_REQUIRED");
        long operation = jdbc.queryForObject("""
                SELECT operation_id FROM production_media_item WHERE production_request_id = ? AND kind = 'IMAGE'
                  AND operation_id IS NOT NULL
                """, Long.class, id);
        assertThat(jdbc.queryForObject("SELECT state FROM production_media_item WHERE operation_id = ?", String.class,
                operation)).isEqualTo("ACTIVE");
        for (int restart = 0; restart < 2; restart++) {
            root().update("UPDATE production_request SET lease_until = NULL, lease_owner = NULL WHERE id = ?", id);
            makeDue(id);
            drain();
        }
        assertStatus(id, "RECOVERY_REQUIRED");
        assertThat(mediaDispatches).hasValue(1);
        assertThat(operations(id, ProductionStage.IMAGE)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT o.status, o.evidence_hash, r.status AS reservation_status FROM provider_operation o
                JOIN budget_reservation r ON r.id = o.reservation_id WHERE o.id = ?
                """, operation)).containsEntry("status", "UNKNOWN").containsEntry("evidence_hash", null)
                .containsEntry("reservation_status", "ACTIVE");
        assertThat(settlements(operation)).isZero();
    }

    /**
     * B02: QC attempts 1 and 2 fail locally, attempt 3 passes and commits QC_REPORT, MANIFEST and the APPLIED QC
     * item, then the process stops before the QC success transaction (the trigger aborts it; the executor leaves the
     * lease to expire as after a JVM exit). Returns the stopped lease epoch.
     */
    long stopAfterLastQcResult(long id) {
        onLocalWork.set(context -> {
            if (context.stage() == ProductionStage.QC && context.attemptNo() < 3) {
                throw new IllegalStateException("IT local QC failure on attempt " + context.attemptNo());
            }
        });
        var root = root();
        root.execute("""
                CREATE TRIGGER it_b02_stop_before_qc_success BEFORE UPDATE ON production_step FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.stage = 'QC' AND NEW.status = 'SUCCEEDED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT stop before the QC success commit';
                    END IF;
                END
                """.formatted(id));
        try {
            for (int round = 0; round < 4; round++) {
                drain();
                makeDue(id);
            }
        } finally {
            root.execute("DROP TRIGGER it_b02_stop_before_qc_success");
            onLocalWork.set(context -> {});
        }
        assertThat(step(id, ProductionStage.QC)).as(describe(id)).containsEntry("status", "RUNNING")
                .containsEntry("attempts", 3);
        assertThat(jdbc.queryForMap("SELECT state, attempt_no FROM production_media_item WHERE production_request_id = ?"
                + " AND kind = 'QC'", id)).containsEntry("state", "APPLIED").containsEntry("attempt_no", 3);
        assertThat(mediaAssetStates(id)).containsEntry("MP4", "STAGED").containsEntry("QC_REPORT", "STAGED")
                .containsEntry("MANIFEST", "STAGED");
        long epoch = jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id);
        root.update("UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        return epoch;
    }

    Map<String, Object> mediaAssetStates(long id) {
        Map<String, Object> states = new java.util.TreeMap<>();
        for (var row : jdbc.queryForList("""
                SELECT a.role, a.state FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND i.kind IN ('RENDER', 'QC') AND a.state <> 'ABANDONED'
                """, id)) {
            states.merge(row.get("role").toString(), row.get("state"), (a, b) -> a + "," + b);
        }
        return states;
    }

    @Test
    void qcResultCommittedOnTheLastAttemptIsFinalizedAfterRestartWithoutAFourthAttempt() throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        long stoppedEpoch = stopAfterLastQcResult(id);
        int renders = ((Number) step(id, ProductionStage.RENDER).get("attempts")).intValue();
        int sent = mediaDispatches.get();
        var stopped = new ProductionExecutor.Lease(id, "planning-it", stoppedEpoch);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.drive(stopped))
                .isInstanceOf(LeaseLostException.class);

        assertThat(executor.runOnce("restarted-worker")).isTrue();
        assertStatus(id, "SUCCEEDED");
        assertThat(step(id, ProductionStage.QC)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 3);
        assertThat(step(id, ProductionStage.RENDER).get("attempts")).isEqualTo(renders);
        assertThat(mediaDispatches).hasValue(sent);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND i.kind IN ('RENDER', 'QC')
                """, Integer.class, id)).as("no re-render and no new QC bundle").isEqualTo(3);
        assertThat(mediaAssetStates(id)).containsEntry("MP4", "FINAL").containsEntry("QC_REPORT", "FINAL")
                .containsEntry("MANIFEST", "FINAL");
        long video = jdbc.queryForObject("""
                SELECT a.id FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role = 'MP4'
                """, Long.class, id);
        mvc.perform(get(PATH + "/" + id + "/media/" + video + "?download=true").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("fixture bytes"));
        mvc.perform(get(PATH + "/" + id + "/media/" + video).with(ApiTestSupport.as(admin)).header("Range", "bytes=0-6"))
                .andExpect(status().isPartialContent());
        // neither the stopped worker nor a second confirmation can finalize again
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.drive(stopped))
                .isInstanceOf(LeaseLostException.class);
        assertThat(executor.runOnce("late-worker")).isFalse();
        assertThat(mediaAssetStates(id)).containsEntry("MP4", "FINAL");
    }

    @ParameterizedTest
    @ValueSource(strings = { "QC_REPORT", "MANIFEST" })
    void damagedQcResultOfTheLastAttemptKeepsPublicationBlocked(String role) throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        stopAfterLastQcResult(id);
        String path = jdbc.queryForObject("""
                SELECT a.relative_path FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role = ?
                """, String.class, id, role);
        java.nio.file.Files.writeString(Path.of(mediaStorageRoot).resolve(path), "altered bytes");
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.QC)).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("last_error_code", "MEDIA_QC_INTEGRITY");
        assertThat(mediaAssetStates(id)).containsEntry("MP4", "STAGED");
        long video = jdbc.queryForObject("""
                SELECT a.id FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role = 'MP4'
                """, Long.class, id);
        mvc.perform(get(PATH + "/" + id + "/media/" + video).with(ApiTestSupport.as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void lastQcAttemptStoppedBeforeItsResultStillFailsAndPublishesNothing() throws Exception {
        discover(NEUTRON);
        long id = create("SHORTS", ",\"candidateCount\":5,\"mediaEnabled\":true");
        onLocalWork.set(context -> {
            if (context.stage() == ProductionStage.QC) {
                if (context.attemptNo() < 3) {
                    throw new IllegalStateException("IT local QC failure on attempt " + context.attemptNo());
                }
                throw new SimulatedStop();
            }
        });
        try {
            for (int round = 0; round < 4; round++) {
                try {
                    drain();
                } catch (SimulatedStop expected) {
                    break;
                }
                makeDue(id);
            }
        } finally {
            onLocalWork.set(context -> {});
        }
        assertThat(step(id, ProductionStage.QC)).as(describe(id)).containsEntry("status", "RUNNING")
                .containsEntry("attempts", 3);
        assertThat(jdbc.queryForObject("SELECT state FROM production_media_item WHERE production_request_id = ?"
                + " AND kind = 'QC'", String.class, id)).isNotEqualTo("APPLIED");
        root().update("UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                id);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.QC)).containsEntry("status", "FAILED").containsEntry("attempts", 3)
                .containsEntry("last_error_code", "INTERRUPTED");
        assertThat(mediaAssetStates(id)).doesNotContainKeys("QC_REPORT", "MANIFEST").containsEntry("MP4", "STAGED");
    }

    @Test
    void unknownPlanningDispatchIsQuarantinedAndConfirmedNonExecutionIsSettled() throws Exception {
        long id = shorts(VENUS);
        doAnswer(call -> { throw new IllegalStateException("simulated response loss"); }).when(provider).research(any());
        drain();
        assertStatus(id, "RECOVERY_REQUIRED");
        long operation = jdbc.queryForObject("""
                SELECT o.id FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = 'RESEARCH' AND o.status = 'UNKNOWN'
                """, Long.class, id);
        makeDue(id);
        drain();
        verify(provider).research(any());
        assertThat(operations(id, ProductionStage.SCRIPT)).isZero();
        var evidence = new ProductionCostPort.Evidence(ProductionCostPort.Outcome.NOT_EXECUTED, null, "KRW",
                BigDecimal.ZERO, null, null, "Fixture confirms the operation did not run");
        var receipt = cost.reconcile(operation, "verified-test-no-execution", evidence);
        assertThat(cost.reconcile(operation, "verified-test-no-execution", evidence)).isEqualTo(receipt);
        makeDue(id);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.RESEARCH).get("last_error_code")).isEqualTo("EFFECT_NOT_EXECUTED");
        verify(provider).research(any());
    }

    @Test
    void planningCannotDispatchAfterTheRequestBudgetIsExhausted() throws Exception {
        long id = shorts(VENUS);
        jdbc.update("UPDATE production_request SET limit_krw = 1 WHERE id = ?", id);
        drain();
        assertStatus(id, "FAILED");
        assertThat(step(id, ProductionStage.RESEARCH).get("last_error_code")).isEqualTo("EFFECT_REJECTED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isZero();
        verify(provider, never()).research(any());
    }

    @Test
    void freezesTwentyScriptsAcrossNewAdoptionsAndReceiptReplay() throws Exception {
        long id = shorts(NEUTRON);
        var history = new ArrayList<Long>();
        for (int i = 0; i < 21; i++) {
            history.add(0, addAdoptedScript(id, i));
        }
        history.addFirst(history.removeLast());
        var expected = List.copyOf(history.subList(0, 20));
        var presented = new ArrayList<ScriptInput>();
        var applyAttempts = new AtomicInteger();
        try (var otherWorker = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                presented.add(call.getArgument(1, ScriptInput.class));
                if (presented.size() == 1) {
                    otherWorker.submit(() -> addAdoptedScript(id, 21)).get(10, TimeUnit.SECONDS);
                }
                return call.callRealMethod();
            }).when(port).script(any(), any(), any());
            doAnswer(call -> {
                Object result = call.callRealMethod();
                var receipt = call.getArgument(1, ProductionCostPort.Receipt.class);
                if (receipt.resultRef().startsWith("planning:") && presented.size() == 1
                        && applyAttempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("interrupt SCRIPT apply after settlement");
                }
                return result;
            }).when(AopTestUtils.<PlanningExecutionPort>getUltimateTargetObject(port)).apply(any(), any(), any());
            assertThat(executor.runOnce("comparison-worker")).isTrue();
            assertStatus(id, "RECOVERY_REQUIRED");
            assertThat(step(id, ProductionStage.SCRIPT)).containsEntry("last_error_code", "RECEIPT_APPLY_FAILED");
            var stored = jdbc.queryForMap("""
                    SELECT r.input_json, r.input_hash, o.receipt_event_id FROM production_stage_result r
                    JOIN provider_operation o ON o.id = r.operation_id
                    JOIN production_step s ON s.id = r.step_id WHERE r.request_id = ? AND s.stage = 'SCRIPT'
                    """, id);
            assertThat(((String) stored.get("input_hash"))).isEqualTo(PlanningJson.encode(presented.getFirst()).hash());
            assertThat(stored.get("receipt_event_id")).isNotNull();
            otherWorker.submit(() -> addAdoptedScript(id, 22)).get(10, TimeUnit.SECONDS);
            makeDue(id);
            assertThat(executor.runOnce("comparison-recovery")).isTrue();
            assertStatus(id, "SUCCEEDED");
            assertThat(presented).hasSize(2).containsOnly(presented.getFirst());
            assertThat(presented.getFirst().recentScripts()).extracting(c -> c.ref().operationId())
                    .containsExactlyElementsOf(expected);
            assertThat(stored.get("input_json")).isEqualTo(PlanningJson.encode(presented.getFirst()).json());
            assertThat(jdbc.queryForMap("""
                    SELECT o.receipt_event_id, o.applied_at FROM provider_operation o
                    JOIN production_step s ON s.id = o.production_step_id
                    WHERE o.production_request_id = ? AND s.stage = 'SCRIPT'
                    """, id)).containsEntry("receipt_event_id", stored.get("receipt_event_id"))
                    .doesNotContainEntry("applied_at", null);
            assertThat(operations(id, ProductionStage.SCRIPT)).isEqualTo(1);
            verify(provider).writeScript(presented.getFirst());
        }
    }

    private long addAdoptedScript(long request, int ordinal) {
        // Give the lowest operation ID the latest timestamp; other rows exercise the ID tie-break.
        String key = "comparison-" + request + "-" + ordinal;
        var reservation = reservations.reserve(new BudgetReservationService.ReserveCommand(
                key, null, "HISTORY_FIXTURE", "KRW", BigDecimal.ONE)).reservation();
        reservations.confirm(new BudgetReservationService.EventCommand(reservation.id(), key, BigDecimal.ONE,
                key, null, null, null, null, Actor.system()));
        long receipt = jdbc.queryForObject("SELECT id FROM cost_log WHERE reservation_id = ? AND entry_type = 'CONFIRM'",
                Long.class, reservation.id());
        return transactions.execute(() -> {
            jdbc.update("""
                    INSERT INTO production_request (channel_id, channel_config_id, requested_by, content_type,
                        pipeline_version, request_key, request_hash, limit_krw, status, created_at)
                    SELECT channel_id, channel_config_id, requested_by, content_type, pipeline_version, ?,
                        SHA2(?, 256), limit_krw, 'SUCCEEDED', UTC_TIMESTAMP(6) FROM production_request WHERE id = ?
                    """, key, key, request);
            long prior = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
            jdbc.update("INSERT INTO production_step (production_request_id, stage, status) VALUES (?, 'SCRIPT', 'SUCCEEDED')",
                    prior);
            long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ?",
                    Long.class, prior);
            jdbc.update("""
                    INSERT INTO provider_operation (production_request_id, production_step_id, effect_key, request_hash,
                        reservation_id, status, dispatched_at, evidence_hash, evidence_json, receipt_outcome,
                        receipt_event_id, receipt_at, applied_at)
                    VALUES (?, ?, SHA2(?, 256), SHA2(?, 256), ?, 'SETTLED', '2024-12-31 00:00:00',
                        SHA2('{}', 256), '{}', 'RESULT_ADOPTED', ?, '2024-12-31 00:00:00', ?)
                    """, prior, step, key, key, reservation.id(), receipt,
                    ordinal == 0 ? "2025-01-02 00:00:00" : "2025-01-01 00:00:00");
            long operation = jdbc.queryForObject("SELECT id FROM provider_operation WHERE reservation_id = ?",
                    Long.class, reservation.id());
            var script = PlanningJson.body(new Script("en", List.of(new Segment("intro",
                    "Paint a bridge with yellow stripes", List.of())), List.of("intro"), 45_000,
                    "speech-estimate-1", "Painting a bridge"));
            var validation = PlanningJson.body(new Validation(List.of()));
            jdbc.update("""
                    INSERT INTO production_stage_result (operation_id, request_id, step_id, schema_version,
                      input_hash, input_json, body_hash, body_json, validation_json, created_at)
                    VALUES (?, ?, ?, 1, SHA2('{}', 256), '{}', ?, ?, ?, UTC_TIMESTAMP(6))
                    """, operation, prior, step, script.hash(), script.json(), validation.json());
            jdbc.update("UPDATE production_step SET result_ref = ? WHERE id = ?", "planning:" + operation, step);
            return operation;
        });
    }

    @Test
    void aSecondWorkerCannotDispatchTheOwnedPlanningStep() throws Exception {
        long id = shorts(NEUTRON);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Worker contention test timed out");
            }
            return call.callRealMethod();
        }).when(provider).research(any());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var first = workers.submit(() -> executor.runOnce("active-worker"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.runOnce("competing-worker")).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(first.get(8, TimeUnit.SECONDS)).isTrue();
        }
        drain();
        assertStatus(id, "SUCCEEDED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isEqualTo(1);
        verify(provider).research(any());
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
        assertThat(selectedSeconds(fixed)).isEqualTo(45); // the earlier FAKE length, inside the default 40-60 s
        long discovery = shorts(NEUTRON);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-discovery-1' WHERE id = ?", discovery);
        drain();
        assertStatus(discovery, "SUCCEEDED");
        verify(provider, never()).research(any());
        assertThat(planning(discovery).path("results")).isEmpty();
        assertThat(planning(fixed).path("results")).isEmpty();
        assertThat(step(discovery, ProductionStage.RESEARCH).get("result_ref").toString()).doesNotStartWith("planning:");
    }

    // ------------------------------------------------------------------ fixed FAKE SELECT length (F06)

    /** The target length the SELECT step wrote into the content revision it created. */
    int selectedSeconds(long id) {
        return jdbc.queryForObject("""
                SELECT cr.target_duration_seconds FROM production_request r
                JOIN content_revision cr ON cr.content_id = r.content_id
                WHERE r.id = ? ORDER BY cr.revision_no LIMIT 1
                """, Integer.class, id);
    }

    long longform() throws Exception {
        policies.create("CONTENT_TYPE", channel, "LONGFORM", "LIFETIME", new BigDecimal("100"), "UTC",
                null, Actor.admin(admin.id()));
        return create("LONGFORM", "");
    }

    void lengths(int shortsMin, int shortsMax, int longMin, int longMax) {
        int base = channels.currentConfig(channel).revisionNo();
        channels.createConfigRevision(channel, base, new ChannelSettings("SHORTS", shortsMin, shortsMax, longMin,
                longMax, DiscoveryFixtures.STRATEGY, DiscoveryFixtures.AUDIENCE, "Energetic and clear",
                "Clean infographic", true, null), "length change", Actor.admin(admin.id()));
    }

    /** The V2-01 FAKE path after SELECT: every later stage still runs, with FAKE results and no planning. */
    void assertFakePipelineCompleted(long id) throws Exception {
        assertStatus(id, "SUCCEEDED");
        assertThat(step(id, ProductionStage.SELECT).get("status")).isEqualTo("SUCCEEDED");
        assertThat(operations(id, ProductionStage.RESEARCH)).isPositive();
        assertThat(step(id, ProductionStage.RESEARCH).get("result_ref").toString()).doesNotStartWith("planning:");
        assertThat(step(id, ProductionStage.RENDER).get("result_ref").toString()).startsWith("fake:render:");
        assertThat(planning(id).path("results")).isEmpty();
    }

    @Test
    void legacyLongformSelectsInsideTheDefaultFrozenRange() throws Exception {
        long id = longform();
        drain();
        assertFakePipelineCompleted(id);
        assertThat(selectedSeconds(id)).isEqualTo(600); // midpoint of the default 480-720 s
        verify(provider, never()).research(any());
    }

    @Test
    void fixedFakeSelectionUsesACustomValidRangeForEitherContentType() throws Exception {
        lengths(20, 30, 900, 1000);
        long longform = longform();
        long shorts = queued(TARDIGRADE);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-fixed-1' WHERE id = ?", shorts);
        drain();
        assertFakePipelineCompleted(longform);
        assertFakePipelineCompleted(shorts);
        assertThat(selectedSeconds(longform)).isEqualTo(950);
        assertThat(selectedSeconds(shorts)).isEqualTo(25);
    }

    @Test
    void fixedFakeSelectionKeepsTheRequestsFrozenRangeAfterTheChannelChanges() throws Exception {
        long frozen = longform();
        long sameConfig = create("LONGFORM", "");
        lengths(40, 60, 2000, 3000); // after both requests were created
        long later = create("LONGFORM", "");
        drain();
        for (long id : List.of(frozen, sameConfig, later)) {
            assertFakePipelineCompleted(id);
        }
        assertThat(selectedSeconds(frozen)).isEqualTo(600);
        assertThat(selectedSeconds(sameConfig)).isEqualTo(600);
        assertThat(selectedSeconds(later)).isEqualTo(2500);
        Long config = jdbc.queryForObject("SELECT channel_config_id FROM production_request WHERE id = ?",
                Long.class, frozen);
        assertThat(jdbc.queryForObject("SELECT channel_config_id FROM production_request WHERE id = ?", Long.class,
                sameConfig)).isEqualTo(config);
        // replaying SELECT of a linked request reads the same frozen revision and rebuilds the same selection
        var replay = new StageWork.StepContext(frozen, 1, ProductionStage.SELECT, "video", 1, 2, channel, "LONGFORM",
                null, null);
        FakeStageWork work = new FakeStageWork(BigDecimal.ONE, FakeStageWork.frozenRanges(jdbc));
        assertThat(work.select(replay)).isEqualTo(work.select(replay));
        assertThat(work.select(replay).targetDurationSeconds()).isEqualTo(600);
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
