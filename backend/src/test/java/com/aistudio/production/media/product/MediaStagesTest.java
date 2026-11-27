package com.aistudio.production.media.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionStage;

/** Catalog validation, QC parsers and the paid-effect boundary of IMAGE/TTS with the in-memory ports. */
class MediaStagesTest {
    @TempDir
    Path temp;

    static Map<String, Object> rights() {
        Map<String, Object> rights = new LinkedHashMap<>();
        rights.put("source", "self");
        rights.put("provider", "AI_STUDIO_SELF_MADE");
        rights.put("license", "own work");
        rights.put("evidenceRef", "ref");
        rights.put("verifiedBy", "tester");
        rights.put("verifiedAt", "2026-11-27T00:00:00Z");
        rights.put("commercialUse", true);
        rights.put("publish", true);
        rights.put("redistribution", true);
        rights.put("conditions", "");
        return rights;
    }

    /** Writes a catalog for the neutron-star topic: images for every scene, optional bad image for scene 2. */
    Path catalog(boolean badSecondImage, boolean withNarration, Map<String, Object> rightsOverride) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("fixtures/test-cat"));
        Files.createDirectories(dir.resolve("images"));
        Files.createDirectories(dir.resolve("audio"));
        var planned = ProductTestKit.planned("neutron-star");
        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> narrations = new ArrayList<>();
        for (var scene : planned.scenes().scenes()) {
            byte[] bytes = badSecondImage && scene.order() == 2
                    ? java.util.Arrays.copyOf(MediaPrimitivesTest.image(1080, 1920, "png"), 100)
                    : MediaPrimitivesTest.image(1080, 1920, "png");
            String file = "images/s" + scene.order() + ".png";
            Files.write(dir.resolve(file), bytes);
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("id", "img-" + scene.order());
            image.put("promptSha256", FixtureCatalog.sha256(scene.imagePrompt().getBytes(StandardCharsets.UTF_8)));
            image.put("file", file);
            image.put("mimeType", "image/png");
            image.put("sha256", FixtureCatalog.sha256(bytes));
            image.put("bytes", bytes.length);
            image.put("rights", rightsOverride == null ? rights() : rightsOverride);
            images.add(image);
            if (withNarration) {
                byte[] wav = MediaPrimitivesTest.wav(22050, 1, MediaTimingAndGraphTest.speech(22050 * 3), false);
                String audio = "audio/n" + scene.order() + ".wav";
                Files.write(dir.resolve(audio), wav);
                List<Map<String, Object>> segments = new ArrayList<>();
                long at = 0;
                long step = 22050L * 3 / scene.segmentIds().size();
                var script = planned.script().segments().stream().collect(java.util.stream.Collectors.toMap(
                        s -> s.id(), s -> s.narration()));
                for (String id : scene.segmentIds()) {
                    segments.add(Map.of("segmentId", id, "cues", List.of(Map.of("text", script.get(id),
                            "startSample", at, "endSample", at + step - 10))));
                    at += step;
                }
                Map<String, Object> narration = new LinkedHashMap<>();
                narration.put("id", "n-" + scene.order());
                narration.put("voiceId", "voice-1");
                narration.put("language", "en");
                narration.put("text", scene.narration());
                narration.put("file", audio);
                narration.put("sha256", FixtureCatalog.sha256(wav));
                narration.put("bytes", wav.length);
                narration.put("sampleRate", 22050);
                narration.put("timingSource", "VERIFIED_FIXTURE");
                narration.put("segments", segments);
                narration.put("emphasis", List.of());
                narration.put("rights", rights());
                narrations.add(narration);
            }
        }
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("schemaVersion", 1);
        catalog.put("catalogId", "test-cat");
        catalog.put("images", images);
        catalog.put("narrations", narrations);
        catalog.put("audio", List.of());
        catalog.put("fonts", List.of());
        Files.writeString(dir.resolve("catalog.json"), ProductTestKit.JSON.writeValueAsString(catalog));
        return temp.resolve("fixtures");
    }

    /** A process runner that only answers the TTS ffprobe cross-check, from the scratch copy. */
    MediaProcessRunner probeRunner() {
        return new MediaProcessRunner() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Path prepare(Scratch scratch) throws java.io.IOException {
                return Files.createDirectories(temp.resolve("scratch/" + scratch.itemId()));
            }

            @Override
            public Result run(Command command, java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
                var wav = Wav.parse(Files.readAllBytes(prepare(command.scratch()).resolve("probe-source.wav")));
                return new Result(0, "{\"streams\":[{\"codec_name\":\"pcm_s16le\",\"sample_rate\":\"" + wav.sampleRate()
                        + "\",\"channels\":" + wav.channels() + ",\"duration_ts\":" + wav.frames() + "}]}", "");
            }
        };
    }

    record Run(ProductTestKit kit, ProductMediaWork work, ProductTestKit.Planned planned, PlanningJson.Encoded profile) {
        MediaStageWork.Result run(ProductionStage stage) {
            return work.execute(kit.context(stage, planned, profile, ProductTestKit.items(stage, planned, profile), 1));
        }
    }

    Run run(Path fixtures, MediaProcessRunner runner) {
        var kit = new ProductTestKit(temp);
        var planned = ProductTestKit.planned("neutron-star");
        var profile = ProductTestKit.profile("test-cat", "voice-1");
        var support = new StageSupport(ProductTestKit.JSON, ProductTestKit.planning(planned),
                ProductTestKit.settings(fixtures.toString(), "", ""), kit.assets(), ProductTestKit.provider(runner));
        var metadata = new MetadataStage(support, ProductTestKit.workItems());
        var work = new ProductMediaWork(new EffectStages(support, kit.effects()), new SubtitleStage(support),
                new RenderStage(support), metadata, new QcStage(support, metadata));
        return new Run(kit, work, planned, profile);
    }

    @Test
    void imageStageSendsOnceperSceneAndStoresSourceAndNormalisedFiles() throws Exception {
        var run = run(catalog(false, false, null), null);
        var result = run.run(ProductionStage.IMAGE);
        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        int scenes = run.planned().scenes().scenes().size();
        assertThat(run.kit().dispatches).hasValue(scenes);
        assertThat(run.kit().assets).filteredOn(a -> a.role() == Role.NORMALIZED_IMAGE).hasSize(scenes);
        var lineage = ProductTestKit.JSON.readTree(run.kit().assets.stream()
                .filter(a -> a.role() == Role.NORMALIZED_IMAGE).findFirst().orElseThrow().lineage());
        assertThat(lineage.path("normalization").path("mode").asString()).isEqualTo("SCALE");
        assertThat(lineage.path("mode").asString()).isEqualTo("FAKE");
    }

    @Test
    void missingFixtureBlocksBeforeAnyReservationOrDispatch() throws Exception {
        var run = run(catalog(false, false, null), probeRunner());
        var result = run.run(ProductionStage.TTS);
        assertThat(result).isEqualTo(new MediaStageWork.Result(Outcome.BLOCKED, "MEDIA_TTS_FIXTURE_MISSING"));
        assertThat(run.kit().dispatches).hasValue(0);
    }

    @Test
    void malformedProviderResultIsSettledAsExecutedFailureWithoutResending() throws Exception {
        var run = run(catalog(true, false, null), null);
        var result = run.run(ProductionStage.IMAGE);
        assertThat(result).isEqualTo(new MediaStageWork.Result(Outcome.BLOCKED, "MEDIA_IMAGE_INVALID"));
        assertThat(run.kit().dispatches).hasValue(2);
        assertThat(run.kit().assets).filteredOn(a -> a.role() == Role.NORMALIZED_IMAGE).hasSize(1);
    }

    @Test
    void itemsPastTheExecutionProofAreOnlyRecovered() throws Exception {
        var run = run(catalog(false, false, null), null);
        var items = ProductTestKit.items(ProductionStage.IMAGE, run.planned(), run.profile()).stream()
                .map(i -> new MediaStageWork.Item(i.id(), i.ordinal(), i.inputJson(), i.inputHash(), "PROVEN", 77L)).toList();
        var result = run.work().recover(run.kit().context(ProductionStage.IMAGE, run.planned(), run.profile(), items, 2));
        assertThat(result).isEqualTo(new MediaStageWork.Result(Outcome.RECOVERY_REQUIRED, "MEDIA_EFFECT_PENDING"));
        assertThat(run.kit().dispatches).hasValue(0);
    }

    @Test
    void boundItemsBeforeTheExecutionProofArePresentedAgainToTheSharedPort() throws Exception {
        // B01: an ACTIVE item may hold a reservation that was never dispatched; the shared port decides whether the
        // same operation is sent, closed as not executed, or kept for recovery.
        var run = run(catalog(false, false, null), null);
        var items = ProductTestKit.items(ProductionStage.IMAGE, run.planned(), run.profile()).stream()
                .map(i -> new MediaStageWork.Item(i.id(), i.ordinal(), i.inputJson(), i.inputHash(), "ACTIVE", 77L)).toList();
        var result = run.work().recover(run.kit().context(ProductionStage.IMAGE, run.planned(), run.profile(), items, 2));
        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(run.kit().dispatches).hasValue(items.size());
    }

    @Test
    void speechStageResamplesToTheSampleClockAndRecordsVerifiedTiming() throws Exception {
        var run = run(catalog(false, true, null), probeRunner());
        assertThat(run.run(ProductionStage.TTS).outcome()).isEqualTo(Outcome.APPLIED);
        var pcm = run.kit().assets.stream().filter(a -> a.role() == Role.PCM_AUDIO).findFirst().orElseThrow();
        assertThat(Wav.parse(pcm.bytes()).sampleRate()).isEqualTo(48_000);
        assertThat(Wav.parse(pcm.bytes()).frames()).isEqualTo(144_000);
        var alignment = ProductTestKit.JSON.readValue(run.kit().assets.stream().filter(a -> a.role() == Role.ALIGNMENT)
                .findFirst().orElseThrow().bytes(), Alignment.class);
        assertThat(alignment.timingSource()).isEqualTo(Alignment.VERIFIED);
        assertThat(alignment.pcmSha256()).isEqualTo(FixtureCatalog.sha256(pcm.bytes()));
    }

    @Test
    void frozenInputFromAnotherRequestOrProfileIsRejected() throws Exception {
        var run = run(catalog(false, false, null), null);
        var other = ProductTestKit.profile("test-cat", "voice-2");
        var items = ProductTestKit.items(ProductionStage.IMAGE, run.planned(), other);
        var result = run.work().execute(run.kit().context(ProductionStage.IMAGE, run.planned(), run.profile(), items, 1));
        assertThat(result).isEqualTo(new MediaStageWork.Result(Outcome.BLOCKED, "MEDIA_INPUT_INTEGRITY"));
        assertThat(run.kit().dispatches).hasValue(0);
    }

    @Test
    void catalogRejectsIncompleteRightsPathEscapeAndChangedFiles() throws Exception {
        var noCommercial = rights();
        noCommercial.put("commercialUse", false);
        Path fixtures = catalog(false, false, noCommercial);
        assertThatThrownBy(() -> FixtureCatalog.load(fixtures.toString(), "test-cat", ProductTestKit.JSON))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_RIGHTS_INCOMPLETE"));
        Path good = catalog(false, false, null);
        String json = Files.readString(good.resolve("test-cat/catalog.json"));
        Files.writeString(good.resolve("test-cat/catalog.json"), json.replaceFirst("images/s1.png", "../s1.png"));
        assertThatThrownBy(() -> FixtureCatalog.load(good.toString(), "test-cat", ProductTestKit.JSON))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_FIXTURE_UNAVAILABLE"));
        Files.writeString(good.resolve("test-cat/catalog.json"), json);
        var loaded = FixtureCatalog.load(good.toString(), "test-cat", ProductTestKit.JSON);
        var first = loaded.catalog().images().getFirst();
        Files.write(good.resolve("test-cat").resolve(first.file()), new byte[] {1, 2, 3});
        assertThatThrownBy(() -> loaded.read(first.file(), first.sha256(), first.bytes()))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_FIXTURE_INTEGRITY"));
        assertThatThrownBy(() -> FixtureCatalog.load(good.toString(), "../etc", ProductTestKit.JSON))
                .isInstanceOf(MediaFailure.class);
    }

    @Test
    void qcParsersCheckCfrFaststartClippingAndRenderBinding() throws Exception {
        assertThat(QcStage.cfr("1024,512\n0,512\n512,512\n", "1/15360", 3)).isTrue();
        assertThat(QcStage.cfr("0,512\n512,513\n1024,512\n", "1/15360", 3)).isFalse();
        assertThat(QcStage.cfr("0,512\n1024,512\n", "1/15360", 2)).isFalse();
        assertThat(QcStage.cfr("0,512\n", "1/15360", 2)).isFalse();
        Path faststart = temp.resolve("a.mp4");
        Files.write(faststart, new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p', 0, 0, 0, 8, 'm', 'o', 'o', 'v', 0, 0, 0, 8,
                'm', 'd', 'a', 't'});
        assertThat(QcStage.moovBeforeMdat(faststart)).isTrue();
        Files.write(faststart, new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p', 0, 0, 0, 8, 'm', 'd', 'a', 't', 0, 0, 0, 8,
                'm', 'o', 'o', 'v'});
        assertThat(QcStage.moovBeforeMdat(faststart)).isFalse();
        Path raw = temp.resolve("a.raw");
        Files.write(raw, new byte[] {(byte) 0xFF, 0x7F, 0, 0, (byte) 0xFF, 0x7F, 0, 0, (byte) 0xFF, 0x7F, 0, 0});
        assertThat(QcStage.clipped(raw)).isEqualTo(1);
        assertThat(QcStage.field("aistudio;render-1;plan=abc;norm=linear", "norm")).isEqualTo("linear");
        assertThat(QcStage.correlation(new double[] {1, 2, 3, 4}, new double[] {2, 4, 6, 8}, 2, 2)).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(SubtitleStage.parseBox("frame:0\nlavfi.bbox.x1=120\nlavfi.bbox.x2=900\nlavfi.bbox.y1=1400\n"
                + "lavfi.bbox.y2=1500\nframe:1\nlavfi.bbox.x1=100\nlavfi.bbox.x2=950\nlavfi.bbox.y1=1390\nlavfi.bbox.y2=1520\n"))
                .isEqualTo(new SubtitleStage.Box(100, 1390, 950, 1520));
        assertThat(new SubtitleStage.Box(100, 1390, 950, 1520).inside()).isFalse();
        assertThat(new SubtitleStage.Box(108, 230, 972, 1536).inside()).isTrue();
    }
}
