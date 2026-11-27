package com.aistudio.production.media.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.ObjectProvider;

import com.aistudio.production.contract.MediaAssets;
import com.aistudio.production.contract.MediaEffectPort;
import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaProfile;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.MediaStore.FileIdentity;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.media.storage.MediaMetadata;
import com.aistudio.production.media.storage.MediaWorkMapper;
import com.aistudio.production.planning.PlanningResultMapper;
import com.aistudio.production.stage.fake.PlanningFixtures;

import tools.jackson.databind.json.JsonMapper;

/**
 * In-memory stand-ins for the shared ports so the product stages can run end to end in a unit test: an asset store
 * that validates payload metadata like the shared one, a cost port that sends at most once per item and settles
 * malformed results as EXECUTED_FAILED, and a direct process runner for a locally installed FFmpeg.
 */
final class ProductTestKit {
    static final JsonMapper JSON = JsonMapper.builder().build();

    record Asset(long id, ProductionStage stage, Role role, String mime, byte[] bytes, String lineage, String rights,
            boolean candidate) {}

    final List<Asset> assets = new ArrayList<>();
    final AtomicLong ids = new AtomicLong(100);
    final AtomicLong dispatches = new AtomicLong();
    final Path root;
    ProductionStage stage;

    ProductTestKit(Path root) {
        this.root = root;
    }

    // ------------------------------------------------------------------ planning bodies

    record Planned(ProductionPlanning.Script script, ProductionPlanning.Scenes scenes, PlanningJson.Encoded scriptBody,
            PlanningJson.Encoded scenesBody) {}

    static Planned planned(String topicKey) {
        var topic = PlanningFixtures.bundled().byKey(topicKey).orElseThrow();
        var segments = topic.segments().stream()
                .map(s -> new ProductionPlanning.Segment(s.id(), s.narration(), s.claimIds())).toList();
        var script = new ProductionPlanning.Script("en", segments, topic.hookSegmentIds(), 50_000, "timing-1", "");
        Map<String, String> text = new java.util.LinkedHashMap<>();
        segments.forEach(s -> text.put(s.id(), s.narration()));
        List<ProductionPlanning.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < topic.scenes().size(); i++) {
            var spec = topic.scenes().get(i);
            String narration = String.join(" ", spec.segmentIds().stream().map(text::get).toList());
            scenes.add(new ProductionPlanning.Scene("scene-" + (i + 1), i + 1, spec.segmentIds(), 8000, narration,
                    spec.visualDescription(), spec.imagePrompt(), spec.direction(), spec.transition(), narration));
        }
        var body = new ProductionPlanning.Scenes(topic.visualStyle(), scenes);
        return new Planned(script, body, PlanningJson.encode(script), PlanningJson.encode(body));
    }

    static PlanningResultMapper planning(Planned planned) {
        PlanningResultMapper mapper = mock(PlanningResultMapper.class);
        String validation = PlanningJson.encode(new ProductionPlanning.Validation(List.of())).json();
        when(mapper.accepted(anyLong(), eq(ProductionStage.SCRIPT))).thenReturn(new PlanningResultMapper.Applied(1,
                ProductionStage.SCRIPT, planned.scriptBody().hash(), planned.scriptBody().json(), validation));
        when(mapper.accepted(anyLong(), eq(ProductionStage.SCENES))).thenReturn(new PlanningResultMapper.Applied(2,
                ProductionStage.SCENES, planned.scenesBody().hash(), planned.scenesBody().json(), validation));
        return mapper;
    }

    // ------------------------------------------------------------------ contexts

    static PlanningJson.Encoded profile(String catalog, String voice) {
        return PlanningJson.encode(MediaProfile.localFixture(7, "en", catalog, voice));
    }

    MediaStageWork.Context context(ProductionStage stage, Planned planned, PlanningJson.Encoded profile,
            List<MediaStageWork.Item> items, int attempt) {
        this.stage = stage;
        return new MediaStageWork.Context(new StepOwner(1, 10 + stage.ordinal(), "test", 1), 3, 4, 5, 6, attempt, stage,
                profile.json(), profile.hash(), items, () -> false, false);
    }

    static List<MediaStageWork.Item> items(ProductionStage stage, Planned planned, PlanningJson.Encoded profile) {
        List<MediaStageWork.Item> items = new ArrayList<>();
        if (stage == ProductionStage.IMAGE || stage == ProductionStage.TTS) {
            for (var scene : planned.scenes().scenes()) {
                var input = PlanningJson.encode(new StageSupport.ItemInput(1, 1, 5, 6, profile.hash(),
                        planned.scenesBody().hash(), planned.scriptBody().hash(), stage, scene));
                items.add(new MediaStageWork.Item(1000L * stage.ordinal() + scene.order(), scene.order(), input.json(),
                        input.hash(), "PENDING", null));
            }
        } else {
            var input = PlanningJson.encode(new StageSupport.ItemInput(1, 1, 5, 6, profile.hash(),
                    planned.scenesBody().hash(), planned.scriptBody().hash(), stage, null));
            items.add(new MediaStageWork.Item(1000L * stage.ordinal() + 1, 1, input.json(), input.hash(), "PENDING", null));
        }
        return items;
    }

    // ------------------------------------------------------------------ ports

    MediaAssets assets() {
        return new MediaAssets() {
            @Override
            public void publish(MediaStageWork.Context context, List<MediaEffectPort.Payload> payloads) throws IOException {
                for (var payload : payloads) {
                    store(context.stage(), payload, context.stage() == ProductionStage.RENDER
                            || context.stage() == ProductionStage.QC);
                }
            }

            @Override
            public List<Stored> inputs(MediaStageWork.Context context, ProductionStage stage, Role role) {
                boolean qc = context.stage() == ProductionStage.QC;
                return assets.stream().filter(a -> a.stage() == stage && a.role() == role && (qc || !a.candidate()))
                        .map(a -> new Stored(a.id(), a.role(), a.mime(), new FileIdentity(a.bytes().length,
                                FixtureCatalog.sha256(a.bytes())))).toList();
            }

            @Override
            public FileChannel open(MediaStageWork.Context context, Stored asset) throws IOException {
                var found = assets.stream().filter(a -> a.id() == asset.id()).findFirst().orElseThrow();
                Path file = Files.createTempFile(root, "asset-", ".bin");
                Files.write(file, found.bytes());
                return FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE);
            }
        };
    }

    private void store(ProductionStage stage, MediaEffectPort.Payload payload, boolean candidate) throws IOException {
        MediaMetadata.require(payload, JSON);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = payload.content().open()) {
            in.transferTo(out);
        }
        if (out.size() == 0 || out.size() > payload.byteLimit()) {
            throw new IOException("Payload exceeds its limit");
        }
        assets.add(new Asset(ids.incrementAndGet(), stage, payload.role(), payload.mimeType(), out.toByteArray(),
                payload.lineageJson(), payload.rightsJson(), candidate));
    }

    MediaEffectPort effects() {
        return new MediaEffectPort() {
            @Override
            public ProductionCostPort.EffectResult execute(Item item, BigDecimal maximumKrw, FakeProvider provider) {
                long operation = dispatches.incrementAndGet();
                var result = provider.send(operation);
                var outcome = ProductionCostPort.Outcome.RESULT_ADOPTED;
                if (result.payloads().isEmpty()) {
                    outcome = ProductionCostPort.Outcome.EXECUTED_FAILED;
                } else {
                    try {
                        for (var payload : result.payloads()) {
                            store(stage, payload, false);
                        }
                    } catch (IOException | IllegalArgumentException ex) {
                        outcome = ProductionCostPort.Outcome.EXECUTED_FAILED;
                    }
                }
                return new ProductionCostPort.EffectResult(ProductionCostPort.Disposition.RECEIPT,
                        new ProductionCostPort.Receipt(operation, outcome, null, null, operation));
            }

            @Override
            public ProductionCostPort.EffectResult recover(Item item) {
                return new ProductionCostPort.EffectResult(ProductionCostPort.Disposition.RECOVERY_REQUIRED, null);
            }

            @Override
            public void apply(Item item, long operationId) {
                // Receipt application is owned by the shared port.
            }
        };
    }

    static ProductSettings settings(String fixtureRoot, String fontPath, String fontSha) {
        return new ProductSettings(fixtureRoot, fontPath, fontSha, "Noto Sans KR", "0".repeat(64), "0".repeat(64),
                BigDecimal.ONE);
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    static MediaWorkMapper workItems() {
        MediaWorkMapper mapper = mock(MediaWorkMapper.class);
        when(mapper.items(anyLong(), any())).thenReturn(List.of());
        return mapper;
    }

    /** Runs a locally installed FFmpeg directly (tests only; production uses the protected shared runner). */
    static MediaProcessRunner directRunner(Path binaries, Path scratchRoot) {
        return new MediaProcessRunner() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Path prepare(Scratch scratch) throws IOException {
                Path dir = scratchRoot.resolve("r" + scratch.requestId() + "-i" + scratch.itemId() + "-a" + scratch.attempt());
                Files.createDirectories(dir);
                return dir;
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
                Path stdout = Files.createTempFile(dir, "out-", ".txt");
                Path stderr = Files.createTempFile(dir, "err-", ".txt");
                Process process = new ProcessBuilder(args).directory(dir.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")))
                        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
                if (!process.waitFor(Duration.ofMinutes(10).toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("timeout");
                }
                String out = Files.readString(stdout, StandardCharsets.UTF_8);
                String err = Files.readString(stderr, StandardCharsets.UTF_8);
                Files.deleteIfExists(stdout);
                Files.deleteIfExists(stderr);
                return new Result(process.exitValue(), out, err.length() > 65536 ? err.substring(err.length() - 65536) : err);
            }
        };
    }
}
