package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.discovery.DiscoveryFixtures;
import com.aistudio.production.discovery.DiscoveryRules;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.aistudio.audit.AuditService.Actor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V2-04 product media through the real executor, cost engine, media storage and HTTP stream against an isolated
 * MariaDB: DISCOVER to QC for two science topics with the rights-checked FAKE/local fixture catalog and a real FFmpeg.
 * Only the process runner is a test double (direct FFmpeg); the Windows Job Object runner is verified separately
 * by the shared contract. Requires AI_STUDIO_TEST_FFMPEG_DIR, AI_STUDIO_TEST_FIXTURE_ROOT and AI_STUDIO_TEST_FONT;
 * AI_STUDIO_TEST_MEDIA_OUT keeps the final MP4 and reports outside Git.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_TEST_FFMPEG_DIR", matches = ".+")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false",
        "ai-studio.production.executor.lease-duration=PT60S",
        "ai-studio.production.executor.recovery-recheck=PT1H",
        "ai-studio.production.planning-enabled=true",
        "ai-studio.production.media.enabled=true",
        "ai-studio.production.media.fixture-catalog=ifnova-v204-1",
        "ai-studio.production.media.voice-id=piper-en_US-ljspeech-high-1",
        "ai-studio.production.media.font-family=Noto Sans KR" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ProductMediaIT.Ports.class)
class ProductMediaIT {
    private static final String PATH = "/api/v1/production-requests";
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final String APP_USER = "it_media_" + ProcessHandle.current().pid();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    static {
        DB.provisionProductionAccount(APP_USER, APP_PASSWORD);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> DB.server().user());
        registry.add("spring.flyway.password", () -> DB.server().password());
        try {
            String root = Files.createTempDirectory("ai-studio-it-product-").toRealPath().toString();
            registry.add("ai-studio.production.media.storage-root", () -> root);
            String font = System.getenv("AI_STUDIO_TEST_FONT");
            registry.add("ai-studio.production.media.font-path", () -> font);
            registry.add("ai-studio.production.media.font-sha256", () -> sha256(Path.of(font)));
            // A private copy, so a test can remove or damage fixture files without touching the rights-checked set.
            Path fixtures = Files.createTempDirectory("ai-studio-it-fixtures-").toRealPath();
            copyTree(Path.of(System.getenv("AI_STUDIO_TEST_FIXTURE_ROOT")), fixtures);
            FIXTURES = fixtures;
            registry.add("ai-studio.production.media.product.fixture-root", fixtures::toString);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static Path FIXTURES;

    static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path to = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(path, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static String sha256(Path file) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Checked before every FFmpeg/ffprobe process; a test can fail a local attempt there with an IOException. */
    interface ProcessHook {
        void accept(MediaProcessRunner.Command command) throws IOException;
    }

    static final java.util.concurrent.atomic.AtomicReference<ProcessHook> beforeRun =
            new java.util.concurrent.atomic.AtomicReference<>(command -> {});

    @TestConfiguration
    static class Ports {
        @Bean
        @Primary
        ScriptedDiscoveryProvider scriptedDiscoveryProvider() {
            return new ScriptedDiscoveryProvider();
        }

        /** Test-only direct FFmpeg runner with the same scratch naming as the protected shared runner. */
        @Bean
        @Primary
        MediaProcessRunner directFfmpeg() throws IOException {
            Path binaries = Path.of(System.getenv("AI_STUDIO_TEST_FFMPEG_DIR"));
            Path scratch = Files.createTempDirectory("ai-studio-it-scratch-");
            return new MediaProcessRunner() {
                @Override
                public boolean ready() {
                    return true;
                }

                @Override
                public Path prepare(Scratch handle) throws IOException {
                    return Files.createDirectories(scratch.resolve("r" + handle.requestId() + "-i" + handle.itemId()
                            + "-a" + handle.attempt()));
                }

                @Override
                public Result run(Command command, BooleanSupplier cancelled) throws IOException, InterruptedException {
                    beforeRun.get().accept(command);
                    List<String> args = new ArrayList<>();
                    args.add(binaries.resolve(command.tool() == Tool.FFMPEG ? "ffmpeg.exe" : "ffprobe.exe").toString());
                    if (command.tool() == Tool.FFMPEG) {
                        args.add("-nostdin");
                    }
                    args.addAll(command.arguments());
                    Path dir = prepare(command.scratch());
                    Path out = Files.createTempFile(dir, "out-", ".txt");
                    Path err = Files.createTempFile(dir, "err-", ".txt");
                    Process process = new ProcessBuilder(args).directory(dir.toFile())
                            .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
                    process.getOutputStream().close();
                    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                    while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                        if (cancelled.getAsBoolean() || System.nanoTime() > deadline) {
                            process.destroyForcibly().waitFor();
                            throw new IOException("Media process cancelled or timed out");
                        }
                    }
                    String stdout = Files.readString(out, StandardCharsets.UTF_8);
                    String stderr = Files.readString(err, StandardCharsets.UTF_8);
                    deleteCaptured(out);
                    deleteCaptured(err);
                    return new Result(process.exitValue(), stdout,
                            stderr.length() > 65536 ? stderr.substring(stderr.length() - 65536) : stderr);
                }
            };
        }

        /** Windows may briefly hold a just-closed capture file (scanner/indexer); the scratch dir is temporary. */
        private static void deleteCaptured(Path file) throws InterruptedException {
            for (int i = 0; i < 20; i++) {
                try {
                    Files.deleteIfExists(file);
                    return;
                } catch (IOException busy) {
                    Thread.sleep(50);
                }
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionExecutor executor;
    @Autowired ScriptedDiscoveryProvider discovery;
    @Autowired JsonMapper json;
    AdminPrincipal admin;
    long channel;

    @BeforeAll
    void setUp() {
        admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated product media integration", control.get().version(), actor);
    }

    @BeforeEach
    void newChannel() {
        Actor actor = Actor.admin(admin.id());
        ChannelSettings settings = new ChannelSettings("SHORTS", 40, 60, 480, 720, DiscoveryFixtures.STRATEGY,
                DiscoveryFixtures.AUDIENCE, "Deadpan and dry", "Cinematic comedy", true, null);
        channel = channels.create(ApiTestSupport.uniqueKey("ifnova"), "en", "UTC", settings, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", new BigDecimal("100"), "UTC", null, actor);
    }

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
        discovery.responses = input -> new Response(DiscoveryRules.RESPONSE_VERSION, list.subList(0, input.candidateCount()));
    }

    long create() throws Exception {
        String key = ApiTestSupport.uniqueKey("media");
        mvc.perform(post(PATH).with(ApiTestSupport.as(admin)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\",\"candidateCount\":5,"
                                + "\"mediaEnabled\":true}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
    }

    /** Runs the worker until the request is terminal, making retry waits due (bounded). */
    void run(long id) {
        for (int round = 0; round < 12; round++) {
            DiscoveryExecutionIT.drain(executor, "media-it");
            String state = jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id);
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED").contains(state)) {
                return;
            }
            jdbc.update("UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                    + "WHERE production_request_id = ? AND retry_at IS NOT NULL", id);
        }
    }

    String describe(long id) {
        return String.valueOf(jdbc.queryForList("SELECT stage, status, attempts, last_error_code FROM production_step "
                + "WHERE production_request_id = ? ORDER BY id", id));
    }

    @ParameterizedTest
    @CsvSource({
        "How heavy would a teaspoon of neutron star be?, neutron-star, 5",
        "Why is a day on Venus longer than its year?, venus-day, 3" })
    void producesAQcPassedShortThroughTheRealExecutorAndStreamsItWithRanges(String question, String topic, int scenes)
            throws Exception {
        discover(question);
        long id = create();
        run(id);
        assertThat(jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id))
                .as(describe(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_item i JOIN provider_operation o ON o.id = i.operation_id
                WHERE i.production_request_id = ? AND i.kind IN ('IMAGE', 'TTS') AND i.state = 'APPLIED'
                  AND o.receipt_outcome = 'RESULT_ADOPTED' AND o.applied_at IS NOT NULL
                """, Integer.class, id)).isEqualTo(2 * scenes);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage IN ('IMAGE', 'TTS')
                """, Integer.class, id)).as("one simulated paid effect per scene and kind").isEqualTo(2 * scenes);

        JsonNode media = json.readTree(mvc.perform(get(PATH + "/" + id + "/media").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(ApiTestSupport.matchesContract())
                .andReturn().getResponse().getContentAsString());
        assertThat(media.path("qc").path("status").asString()).isEqualTo("SUCCEEDED");
        List<String> roles = new ArrayList<>();
        String video = null;
        String report = null;
        String manifest = null;
        String srt = null;
        for (JsonNode asset : media.path("assets")) {
            roles.add(asset.path("role").asString());
            switch (asset.path("role").asString()) {
                case "MP4" -> video = asset.path("id").asString();
                case "QC_REPORT" -> report = asset.path("id").asString();
                case "MANIFEST" -> manifest = asset.path("id").asString();
                case "SRT" -> srt = asset.path("id").asString();
                default -> { }
            }
        }
        assertThat(roles).filteredOn("NORMALIZED_IMAGE"::equals).hasSize(scenes);
        assertThat(roles).filteredOn("PCM_AUDIO"::equals).hasSize(scenes);
        assertThat(roles).contains("SRT", "MP4", "QC_REPORT", "MANIFEST");

        String videoPath = PATH + "/" + id + "/media/" + video;
        long length = Long.parseLong(mvc.perform(head(videoPath).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andReturn().getResponse().getHeader("Content-Length"));
        mvc.perform(get(videoPath).with(ApiTestSupport.as(admin)).header("Range", "bytes=1000-1999"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 1000-1999/" + length));
        mvc.perform(get(videoPath).with(ApiTestSupport.as(admin)).header("Range", "bytes=-500"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes " + (length - 500) + "-" + (length - 1) + "/" + length));
        mvc.perform(get(videoPath).with(ApiTestSupport.as(admin)).header("Range", "bytes=" + length + "-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + length));
        mvc.perform(get(videoPath + "?download=true").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"asset-" + video + ".mp4\""));
        mvc.perform(get(videoPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/" + id + "/media/" + (Long.parseLong(video) + 100_000)).with(ApiTestSupport.as(admin)))
                .andExpect(status().isNotFound());

        byte[] mp4 = mvc.perform(get(videoPath).with(ApiTestSupport.as(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat((long) mp4.length).isEqualTo(length);
        String qc = mvc.perform(get(PATH + "/" + id + "/media/" + report).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode qcReport = json.readTree(qc);
        assertThat(qcReport.path("status").asString()).startsWith("PASS");
        String out = System.getenv("AI_STUDIO_TEST_MEDIA_OUT");
        if (out != null) {
            Path dir = Files.createDirectories(Path.of(out, "it-" + topic));
            Files.write(dir.resolve(topic + ".mp4"), mp4);
            Files.writeString(dir.resolve("qc-report.json"), qc);
            Files.write(dir.resolve("manifest.json"), mvc.perform(get(PATH + "/" + id + "/media/" + manifest)
                    .with(ApiTestSupport.as(admin))).andReturn().getResponse().getContentAsByteArray());
            Files.write(dir.resolve(topic + ".srt"), mvc.perform(get(PATH + "/" + id + "/media/" + srt)
                    .with(ApiTestSupport.as(admin))).andReturn().getResponse().getContentAsByteArray());
            Files.writeString(dir.resolve("request.txt"), "requestId=" + id + "\n" + describe(id) + "\n");
        }
    }

    // ------------------------------------------------------------------ V2-04 B01/B02 with the product stages

    static final String VENUS = "Why is a day on Venus longer than its year?";
    @Autowired com.aistudio.production.contract.MediaEffectPort mediaEffects;

    JdbcTemplate root() {
        return new JdbcTemplate(DB.dataSource());
    }

    int settlements(long operation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cost_log WHERE event_key = CONCAT('production:settle:', ?)",
                Integer.class, operation);
    }

    /**
     * B01 through the real EffectStages: the dispatch permit of the first {@code stage} operation is aborted, so the
     * reservation and the item binding are the only committed effect state. The park is rewritten to what a JVM exit
     * leaves: step RUNNING and the stopped worker's lease expired. Returns item, operation, step and lease epoch.
     */
    long[] stopAfterReservation(long id, String stage) {
        var root = root();
        root.execute("""
                CREATE TRIGGER it_product_b01_stop BEFORE UPDATE ON provider_operation FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.status = 'DISPATCHED' AND OLD.status = 'RESERVED'
                       AND (SELECT stage FROM production_step WHERE id = NEW.production_step_id) = '%s' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT stop between reservation and dispatch';
                    END IF;
                END
                """.formatted(id, stage));
        try {
            DiscoveryExecutionIT.drain(executor, "media-it");
        } finally {
            root.execute("DROP TRIGGER it_product_b01_stop");
        }
        var bound = jdbc.queryForMap("""
                SELECT i.id AS item_id, i.state, o.id AS operation_id, o.status, s.id AS step_id,
                       IF(o.dispatched_at IS NULL, 'yes', 'no') AS never_sent, r.status AS reservation_status
                FROM production_media_item i JOIN provider_operation o ON o.id = i.operation_id
                JOIN production_step s ON s.id = i.production_step_id
                JOIN budget_reservation r ON r.id = o.reservation_id
                WHERE i.production_request_id = ? AND i.kind = ?
                """, id, stage);
        assertThat(bound).as(describe(id)).containsEntry("state", "ACTIVE").containsEntry("status", "RESERVED")
                .containsEntry("never_sent", "yes").containsEntry("reservation_status", "ACTIVE");
        root.update("""
                UPDATE production_step SET status = 'RUNNING', retry_at = NULL, last_error_code = NULL
                WHERE production_request_id = ? AND stage = ?
                """, id, stage);
        root.update("""
                UPDATE production_request SET status = 'RUNNING', lease_owner = 'stopped-worker',
                    lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?
                """, id);
        long epoch = jdbc.queryForObject("SELECT lease_epoch FROM production_request WHERE id = ?", Long.class, id);
        return new long[] { ((Number) bound.get("item_id")).longValue(), ((Number) bound.get("operation_id")).longValue(),
                ((Number) bound.get("step_id")).longValue(), epoch };
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "IMAGE", "TTS" })
    void productEffectReservedBeforeDispatchResumesOnTheSameOperationAfterRestart(String stage) throws Exception {
        discover(VENUS);
        long id = create();
        long[] stopped = stopAfterReservation(id, stage);
        String key = jdbc.queryForObject("SELECT effect_key FROM provider_operation WHERE id = ?", String.class, stopped[1]);
        var stale = new com.aistudio.production.contract.ProductionCostPort.StepOwner(id, stopped[2], "stopped-worker",
                stopped[3]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaEffects.execute(
                new com.aistudio.production.contract.MediaEffectPort.Item(stale, stopped[0]), BigDecimal.ONE, op -> {
                    throw new AssertionError("a stale worker must not reach the provider");
                })).isInstanceOf(com.aistudio.shared.api.ApiException.class);

        run(id); // new worker and lease epoch, real EffectStages
        assertThat(jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id))
                .as(describe(id)).isEqualTo("SUCCEEDED");
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
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage IN ('IMAGE', 'TTS') AND o.dispatched_at IS NOT NULL
                  AND o.receipt_outcome = 'RESULT_ADOPTED' AND o.applied_at IS NOT NULL
                """, Integer.class, id)).as("one dispatch per scene effect, none added").isEqualTo(items);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage IN ('IMAGE', 'TTS')
                """, Integer.class, id)).isEqualTo(items);
        assertThat(settlements(stopped[1])).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_item WHERE production_request_id = ?
                  AND kind IN ('IMAGE', 'TTS') AND execution_proof_sha256 IS NOT NULL
                """, Integer.class, id)).as("one provider execution proof per item").isEqualTo(items);
        long video = jdbc.queryForObject("""
                SELECT a.id FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role = 'MP4' AND a.state = 'FINAL'
                """, Long.class, id);
        mvc.perform(get(PATH + "/" + id + "/media/" + video).with(ApiTestSupport.as(admin)).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent());
        mvc.perform(get(PATH + "/" + id + "/media/" + video + "?download=true").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void productImageReservationIsClosedWithoutDispatchWhenItsBudgetPeriodEndsBeforeRestart() throws Exception {
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("1000"), "UTC", null, Actor.admin(admin.id()));
        discover(VENUS);
        long id = create();
        long[] stopped = stopAfterReservation(id, "IMAGE");
        assertThat(root().update("""
                UPDATE budget_bucket b JOIN budget_reservation_bucket rb ON rb.bucket_id = b.id
                JOIN budget_policy p ON p.id = b.policy_id
                SET b.period_end = b.period_start + INTERVAL 1 MICROSECOND
                WHERE rb.reservation_id = (SELECT reservation_id FROM provider_operation WHERE id = ?)
                  AND p.scope = 'CHANNEL' AND p.channel_id = ?
                """, stopped[1], channel)).isEqualTo(1);
        run(id);
        assertThat(jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id))
                .as(describe(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("""
                SELECT o.status, o.receipt_outcome, IF(o.dispatched_at IS NULL, 'yes', 'no') AS never_sent,
                       IF(o.applied_at IS NOT NULL, 'yes', 'no') AS applied, r.status AS reservation_status,
                       IF(r.reserved_remaining_krw = 0 AND r.recognized_cost_krw = 0, 'yes', 'no') AS returned
                FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id WHERE o.id = ?
                """, stopped[1])).containsEntry("status", "SETTLED").containsEntry("receipt_outcome", "NOT_EXECUTED")
                .containsEntry("never_sent", "yes").containsEntry("applied", "yes")
                .containsEntry("reservation_status", "RELEASED").containsEntry("returned", "yes");
        assertThat(settlements(stopped[1])).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND dispatched_at IS NOT NULL
                  AND production_step_id = ?
                """, Integer.class, id, stopped[2])).isZero();
    }

    @Test
    void productQcPassCommittedOnTheLastAttemptIsFinalizedAfterRestartWithoutRerender() throws Exception {
        discover(VENUS);
        long id = create();
        var qcItem = new java.util.concurrent.atomic.AtomicLong();
        var processes = new java.util.concurrent.atomic.AtomicInteger();
        beforeRun.set(command -> {
            processes.incrementAndGet();
            if (qcItem.get() == 0) {
                jdbc.queryForList("SELECT id FROM production_media_item WHERE production_request_id = ? AND kind = 'QC'",
                        Long.class, id).forEach(qcItem::set);
            }
            if (command.scratch().itemId() == qcItem.get() && command.scratch().attempt() < 3) {
                throw new IOException("IT local QC failure on attempt " + command.scratch().attempt());
            }
        });
        var root = root();
        root.execute("""
                CREATE TRIGGER it_product_b02_stop BEFORE UPDATE ON production_step FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.stage = 'QC' AND NEW.status = 'SUCCEEDED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT stop before the QC success commit';
                    END IF;
                END
                """.formatted(id));
        try {
            run(id);
        } finally {
            root.execute("DROP TRIGGER it_product_b02_stop");
        }
        assertThat(jdbc.queryForMap("SELECT status, attempts FROM production_step WHERE production_request_id = ?"
                + " AND stage = 'QC'", id)).as(describe(id)).containsEntry("status", "RUNNING")
                .containsEntry("attempts", 3);
        assertThat(jdbc.queryForMap("SELECT state, attempt_no FROM production_media_item WHERE id = ?", qcItem.get()))
                .containsEntry("state", "APPLIED").containsEntry("attempt_no", 3);
        assertThat(jdbc.queryForList("""
                SELECT a.state FROM production_media_asset a JOIN production_media_item i ON i.id = a.item_id
                WHERE i.production_request_id = ? AND a.role IN ('MP4', 'QC_REPORT', 'MANIFEST')
                """, String.class, id)).hasSize(3).containsOnly("STAGED");
        int renders = jdbc.queryForObject("SELECT attempts FROM production_step WHERE production_request_id = ?"
                + " AND stage = 'RENDER'", Integer.class, id);
        root.update("UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        processes.set(0);
        try {
            assertThat(executor.runOnce("restarted-worker")).isTrue();
        } finally {
            beforeRun.set(command -> {});
        }
        assertThat(jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id))
                .as(describe(id)).isEqualTo("SUCCEEDED");
        assertThat(processes).as("no FFmpeg process: no re-render and no new QC run").hasValue(0);
        assertThat(jdbc.queryForMap("SELECT status, attempts FROM production_step WHERE production_request_id = ?"
                + " AND stage = 'QC'", id)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 3);
        assertThat(jdbc.queryForObject("SELECT attempts FROM production_step WHERE production_request_id = ?"
                + " AND stage = 'RENDER'", Integer.class, id)).isEqualTo(renders);
        JsonNode media = json.readTree(mvc.perform(get(PATH + "/" + id + "/media").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(media.path("qc").path("status").asString()).isEqualTo("SUCCEEDED");
        String video = null;
        String report = null;
        for (JsonNode asset : media.path("assets")) {
            switch (asset.path("role").asString()) {
                case "MP4" -> video = asset.path("id").asString();
                case "QC_REPORT" -> report = asset.path("id").asString();
                default -> { }
            }
        }
        assertThat(video).isNotNull();
        mvc.perform(get(PATH + "/" + id + "/media/" + video).with(ApiTestSupport.as(admin)).header("Range", "bytes=1000-1999"))
                .andExpect(status().isPartialContent());
        byte[] mp4 = mvc.perform(get(PATH + "/" + id + "/media/" + video + "?download=true").with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(mp4)).isEqualTo(jdbc.queryForObject("SELECT sha256 FROM production_media_asset WHERE id = ?",
                String.class, Long.parseLong(video)));
        assertThat(json.readTree(mvc.perform(get(PATH + "/" + id + "/media/" + report).with(ApiTestSupport.as(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("status").asString())
                .startsWith("PASS");
        assertThat(executor.runOnce("late-worker")).isFalse();
    }

    // ------------------------------------------------------------------ B01 residual: closing before fixtures

    enum Damage { MISSING, DAMAGED }

    @org.junit.jupiter.api.AfterEach
    void restoreFixtures() throws IOException {
        copyTree(Path.of(System.getenv("AI_STUDIO_TEST_FIXTURE_ROOT")), FIXTURES);
    }

    /** Removes or overwrites every fixture file of the Venus topic used by {@code stage}. */
    void breakFixtures(String stage, Damage damage) throws IOException {
        Path dir = FIXTURES.resolve("ifnova-v204-1").resolve(stage.equals("IMAGE") ? "images" : "audio");
        String prefix = stage.equals("IMAGE") ? "venus-" : "venus-day-";
        int changed = 0;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
                if (damage == Damage.MISSING) {
                    Files.delete(file);
                } else {
                    Files.write(file, "damaged fixture".getBytes(StandardCharsets.UTF_8));
                }
                changed++;
            }
        }
        assertThat(changed).isEqualTo(3);
    }

    void endBudgetPeriod(long operation) {
        assertThat(root().update("""
                UPDATE budget_bucket b JOIN budget_reservation_bucket rb ON rb.bucket_id = b.id
                JOIN budget_policy p ON p.id = b.policy_id
                SET b.period_end = b.period_start + INTERVAL 1 MICROSECOND
                WHERE rb.reservation_id = (SELECT reservation_id FROM provider_operation WHERE id = ?)
                  AND p.scope = 'CHANNEL' AND p.channel_id = ?
                """, operation, channel)).isEqualTo(1);
    }

    void channelDayPolicy() {
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("1000"), "UTC", null, Actor.admin(admin.id()));
    }

    String requestStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id);
    }

    java.util.Map<String, Object> operationState(long operation) {
        return jdbc.queryForMap("""
                SELECT o.status, o.receipt_outcome, o.receipt_event_id, IF(o.dispatched_at IS NULL, 'yes', 'no') AS never_sent,
                       IF(o.applied_at IS NOT NULL, 'yes', 'no') AS applied, r.status AS reservation_status,
                       IF(r.reserved_remaining_krw = 0 AND r.recognized_cost_krw = 0, 'yes', 'no') AS returned
                FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id WHERE o.id = ?
                """, operation);
    }

    void assertClosedWithoutDispatch(long operation) {
        assertThat(operationState(operation)).containsEntry("status", "SETTLED")
                .containsEntry("receipt_outcome", "NOT_EXECUTED").containsEntry("never_sent", "yes")
                .containsEntry("applied", "yes").containsEntry("reservation_status", "RELEASED")
                .containsEntry("returned", "yes");
        assertThat(settlements(operation)).isEqualTo(1);
    }

    void assertNoProviderExecution(long id, long step) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND production_step_id = ?
                  AND dispatched_at IS NOT NULL
                """, Integer.class, id, step)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_media_item WHERE production_request_id = ? AND production_step_id = ?
                  AND execution_proof_sha256 IS NOT NULL
                """, Integer.class, id, step)).isZero();
    }

    void assertStaleWorkerRejected(long id, long[] stopped) {
        var stale = new com.aistudio.production.contract.ProductionCostPort.StepOwner(id, stopped[2], "stopped-worker",
                stopped[3]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaEffects.execute(
                new com.aistudio.production.contract.MediaEffectPort.Item(stale, stopped[0]), BigDecimal.ONE, op -> {
                    throw new AssertionError("a stale worker must not reach the provider");
                })).isInstanceOf(com.aistudio.shared.api.ApiException.class);
    }

    void restartAgain(long id) {
        root().update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
        jdbc.update("UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                + "WHERE production_request_id = ? AND retry_at IS NOT NULL", id);
        DiscoveryExecutionIT.drain(executor, "media-it-again");
    }

    @ParameterizedTest
    @CsvSource({ "IMAGE, EXPIRED, MISSING", "IMAGE, EXPIRED, DAMAGED", "IMAGE, CANCELLED, MISSING",
        "IMAGE, CANCELLED, DAMAGED", "TTS, EXPIRED, MISSING", "TTS, EXPIRED, DAMAGED", "TTS, CANCELLED, MISSING",
        "TTS, CANCELLED, DAMAGED" })
    void endedUndispatchedReservationClosesWithoutDispatchWhateverTheFixtureState(String stage, String ending,
            Damage damage) throws Exception {
        if (ending.equals("EXPIRED")) {
            channelDayPolicy();
        }
        discover(VENUS);
        long id = create();
        long[] stopped = stopAfterReservation(id, stage);
        if (ending.equals("EXPIRED")) {
            endBudgetPeriod(stopped[1]);
        } else {
            jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
        }
        breakFixtures(stage, damage);

        run(id); // new worker and lease epoch, product EffectStages
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo(ending.equals("EXPIRED") ? "FAILED" : "CANCELLED");
        assertClosedWithoutDispatch(stopped[1]);
        assertNoProviderExecution(id, stopped[2]);
        Object receipt = operationState(stopped[1]).get("receipt_event_id");

        restartAgain(id); // repeated recovery: no second receipt or settlement
        assertThat(operationState(stopped[1])).containsEntry("receipt_event_id", receipt);
        assertClosedWithoutDispatch(stopped[1]);
        assertStaleWorkerRejected(id, stopped);
        assertThat(settlements(stopped[1])).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({ "IMAGE, MISSING", "IMAGE, DAMAGED", "TTS, MISSING", "TTS, DAMAGED" })
    void validReservationWithBrokenFixtureIsNotSentAndClosesWhenItsPeriodLaterEnds(String stage, Damage damage)
            throws Exception {
        channelDayPolicy();
        discover(VENUS);
        long id = create();
        long[] stopped = stopAfterReservation(id, stage);
        breakFixtures(stage, damage);

        run(id);
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(operationState(stopped[1])).containsEntry("status", "RESERVED").containsEntry("never_sent", "yes")
                .containsEntry("reservation_status", "ACTIVE");
        assertNoProviderExecution(id, stopped[2]);
        assertThat(settlements(stopped[1])).isZero();

        endBudgetPeriod(stopped[1]);
        restartAgain(id);
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo("FAILED");
        assertClosedWithoutDispatch(stopped[1]);
        assertNoProviderExecution(id, stopped[2]);
        restartAgain(id);
        assertClosedWithoutDispatch(stopped[1]);
        assertStaleWorkerRejected(id, stopped);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "IMAGE", "TTS" })
    void dispatchedOperationWithoutEvidenceIsNeverResentWhateverTheFixtureState(String stage) throws Exception {
        discover(VENUS);
        long id = create();
        var root = root();
        root.execute("""
                CREATE TRIGGER it_product_b01_lost_proof BEFORE UPDATE ON production_media_item FOR EACH ROW
                BEGIN
                    IF NEW.production_request_id = %d AND NEW.kind = '%s' AND NEW.state = 'PROVEN' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IT stop after dispatch before the proof';
                    END IF;
                END
                """.formatted(id, stage));
        try {
            DiscoveryExecutionIT.drain(executor, "media-it");
        } finally {
            root.execute("DROP TRIGGER it_product_b01_lost_proof");
        }
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo("RECOVERY_REQUIRED");
        var sent = jdbc.queryForMap("""
                SELECT i.id AS item_id, o.id AS operation_id, s.id AS step_id, r.lease_epoch
                FROM production_media_item i JOIN provider_operation o ON o.id = i.operation_id
                JOIN production_step s ON s.id = i.production_step_id
                JOIN production_request r ON r.id = i.production_request_id
                WHERE i.production_request_id = ? AND i.kind = ? AND o.dispatched_at IS NOT NULL
                """, id, stage);
        long operation = ((Number) sent.get("operation_id")).longValue();
        long step = ((Number) sent.get("step_id")).longValue();
        assertThat(operationState(operation)).containsEntry("status", "UNKNOWN").containsEntry("never_sent", "no");

        breakFixtures(stage, Damage.DAMAGED);
        restartAgain(id);
        restoreFixtures();
        restartAgain(id);
        assertThat(requestStatus(id)).as(describe(id)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(operationState(operation)).containsEntry("status", "UNKNOWN").containsEntry("reservation_status", "ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ? AND production_step_id = ?
                """, Integer.class, id, step)).as("no further operation for the step").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT execution_proof_sha256 FROM production_media_item WHERE id = ?",
                String.class, ((Number) sent.get("item_id")).longValue())).isNull();
        assertThat(settlements(operation)).isZero();
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
