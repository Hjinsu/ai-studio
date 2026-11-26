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
            registry.add("ai-studio.production.media.product.fixture-root", () -> System.getenv("AI_STUDIO_TEST_FIXTURE_ROOT"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
                    Files.deleteIfExists(out);
                    Files.deleteIfExists(err);
                    return new Result(process.exitValue(), stdout,
                            stderr.length() > 65536 ? stderr.substring(stderr.length() - 65536) : stderr);
                }
            };
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
}
