package com.aistudio.production.media.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionStage;

/**
 * Real FFmpeg product pipeline: IMAGE, TTS, SUBTITLE, RENDER, METADATA and QC for the two acceptance topics with
 * the rights-checked fixture catalog. Requires a local FFmpeg build, the Git-external fixture catalog and the static
 * font; skipped otherwise. Set AI_STUDIO_TEST_FFMPEG_DIR, AI_STUDIO_TEST_FIXTURE_ROOT, AI_STUDIO_TEST_FONT and
 * optionally AI_STUDIO_TEST_MEDIA_OUT to keep the produced MP4 and reports.
 */
@Tag("media")
class ProductPipelineFfmpegTest {
    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"neutron-star", "venus-day"})
    void rendersAndPassesTechnicalQc(String topic) throws Exception {
        String ffmpeg = System.getenv("AI_STUDIO_TEST_FFMPEG_DIR");
        String fixtures = System.getenv("AI_STUDIO_TEST_FIXTURE_ROOT");
        String font = System.getenv("AI_STUDIO_TEST_FONT");
        assumeTrue(ffmpeg != null && fixtures != null && font != null, "real FFmpeg media environment not configured");
        String fontSha = FixtureCatalog.sha256(Files.readAllBytes(Path.of(font)));
        var kit = new ProductTestKit(temp);
        var planned = ProductTestKit.planned(topic);
        var profile = ProductTestKit.profile("ifnova-v204-1", "piper-en_US-ljspeech-high-1");
        var runner = ProductTestKit.directRunner(Path.of(ffmpeg), Files.createDirectories(temp.resolve("scratch")));
        var support = new StageSupport(ProductTestKit.JSON, ProductTestKit.planning(planned),
                ProductTestKit.settings(fixtures, font, fontSha), kit.assets(), ProductTestKit.provider(runner));
        var metadata = new MetadataStage(support, ProductTestKit.workItems());
        var work = new ProductMediaWork(new EffectStages(support, kit.effects()), new SubtitleStage(support),
                new RenderStage(support), metadata, new QcStage(support, metadata));
        for (ProductionStage stage : List.of(ProductionStage.IMAGE, ProductionStage.TTS, ProductionStage.SUBTITLE,
                ProductionStage.RENDER, ProductionStage.METADATA, ProductionStage.QC)) {
            var context = kit.context(stage, planned, profile, ProductTestKit.items(stage, planned, profile), 1);
            MediaStageWork.Result result = work.execute(context);
            assertThat(result.outcome()).as(stage + " " + result.code()).isEqualTo(Outcome.APPLIED);
        }
        int scenes = planned.scenes().scenes().size();
        assertThat(kit.dispatches).hasValue(2L * scenes);
        var report = kit.assets.stream().filter(a -> a.role() == Role.QC_REPORT).findFirst().orElseThrow();
        var qc = ProductTestKit.JSON.readTree(new String(report.bytes(), StandardCharsets.UTF_8));
        assertThat(qc.path("status").asString()).startsWith("PASS");
        assertThat(qc.path("integratedLufs").asDouble()).isBetween(-18.0, -14.0);
        assertThat(qc.path("truePeakDbtp").asDouble()).isLessThanOrEqualTo(-1.0);
        String keep = System.getenv("AI_STUDIO_TEST_MEDIA_OUT");
        if (keep != null) {
            Path out = Files.createDirectories(Path.of(keep, topic));
            for (var asset : kit.assets) {
                String name = asset.stage().name().toLowerCase() + "-" + asset.role().name().toLowerCase() + "-" + asset.id()
                        + switch (asset.mime()) {
                            case "video/mp4" -> ".mp4";
                            case "image/png" -> ".png";
                            case "image/jpeg" -> ".jpg";
                            case "audio/wav" -> ".wav";
                            case "application/json" -> ".json";
                            case "application/x-subrip" -> ".srt";
                            default -> ".ass";
                        };
                Files.write(out.resolve(name), asset.bytes());
            }
        }
    }
}
