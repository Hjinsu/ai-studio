package com.aistudio.production.media.product;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaProcessRunner.Purpose;
import com.aistudio.production.contract.MediaProcessRunner.Scratch;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStageWork.Result;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionStage;

import tools.jackson.databind.JsonNode;

/**
 * RENDER (D04): narration-first mix with optional ducked music and placed effects, two-pass loudnorm on the final
 * stereo layout, then the 1080x1920 30 fps CFR H.264 High / AAC-LC 48 kHz faststart MP4 with ASS burn-in. The MP4
 * is recorded as a STAGED candidate; only QC can publish it. The render plan hash and loudnorm result are written
 * into the container comment so QC and the manifest can bind the file to its plan.
 */
@Component
class RenderStage {
    static final String VERSION = "render-1";
    static final long MAX_MP4 = 100L * 1024 * 1024;

    private final StageSupport support;

    RenderStage(StageSupport support) {
        this.support = support;
    }

    record Loudness(String normalizationType, String inputI, String inputTp, String outputI, String outputTp) {}

    Result run(Context context) {
        var item = context.items().getFirst();
        if ("APPLIED".equals(item.state())) {
            return new Result(Outcome.APPLIED, null);
        }
        var input = support.input(context, item);
        var profile = support.profile(context);
        var catalog = support.catalog(profile);
        var inputs = SubtitleStage.inputs(support, context, input);
        int n = inputs.scenes().size();
        var images = support.inputs(context, ProductionStage.IMAGE, Role.NORMALIZED_IMAGE, n);
        var ass = support.inputs(context, ProductionStage.SUBTITLE, Role.ASS, 1).getFirst();
        var fonts = SubtitleStage.fonts(support, catalog);
        RenderPlan plan = RenderPlan.of(inputs.scenes(), inputs.alignments(), inputs.timeline(), catalog.catalog());
        String planSha = FixtureCatalog.sha256(support.encode(plan));

        var tools = MediaTools.open(support.runner(), new Scratch(context.owner().requestId(), item.id(),
                context.attemptNo()), context.cancelled());
        for (int i = 0; i < n; i++) {
            tools.copy(FilterGraphs.image(i), support.open(context, images.get(i)));
            tools.copy(FilterGraphs.narration(i), support.open(context, inputs.pcm().get(i)));
        }
        tools.copy(FilterGraphs.SUBTITLE_FILE, support.open(context, ass));
        tools.write(SubtitleStage.FONT_ALIAS + fonts.extension(), fonts.bytes());
        List<String> mixArgs = new ArrayList<>(List.of("-hide_banner"));
        for (int i = 0; i < n; i++) {
            mixArgs.addAll(List.of("-i", FilterGraphs.narration(i)));
        }
        List<Map<String, Object>> soundInputs = new ArrayList<>();
        if (plan.musicId() != null) {
            var music = fixture(catalog, plan.musicId());
            tools.write(FilterGraphs.sound(music.id()), catalog.read(music.file(), music.sha256(), music.bytes()));
            mixArgs.addAll(List.of("-stream_loop", "-1", "-i", FilterGraphs.sound(music.id())));
            soundInputs.add(Map.of("id", music.id(), "kind", music.kind(), "sha256", music.sha256()));
        }
        for (var sound : plan.sounds()) {
            var fixture = fixture(catalog, sound.fixtureId());
            if (!tools.exists(FilterGraphs.sound(fixture.id()))) {
                tools.write(FilterGraphs.sound(fixture.id()), catalog.read(fixture.file(), fixture.sha256(), fixture.bytes()));
            }
            mixArgs.addAll(List.of("-i", FilterGraphs.sound(fixture.id())));
            soundInputs.add(Map.of("id", fixture.id(), "kind", fixture.kind(), "sha256", fixture.sha256(),
                    "startSample", sound.startSample()));
        }
        String audioGraph = FilterGraphs.audio(plan, n);
        tools.write(FilterGraphs.AUDIO_GRAPH, audioGraph.getBytes(StandardCharsets.US_ASCII));
        for (String alias : List.of(FilterGraphs.MIX_FILE, FilterGraphs.NORMALIZED_AUDIO, FilterGraphs.OUTPUT)) {
            tools.delete(alias);
        }
        mixArgs.addAll(List.of("-/filter_complex", FilterGraphs.AUDIO_GRAPH, "-map", "[aout]", "-c:a", "pcm_f32le",
                FilterGraphs.MIX_FILE));
        tools.ffmpeg(Purpose.RENDER, mixArgs);

        var first = loudnorm(tools.ffmpeg(Purpose.LOUDNESS, List.of("-hide_banner", "-i", FilterGraphs.MIX_FILE,
                "-af", "loudnorm=" + FilterGraphs.LOUDNORM + ":print_format=json", "-f", "null", "-")));
        var measured = new FilterGraphs.Measured(text(first, "input_i"), text(first, "input_tp"),
                text(first, "input_lra"), text(first, "input_thresh"), text(first, "target_offset"));
        var second = loudnorm(tools.ffmpeg(Purpose.LOUDNESS, List.of("-hide_banner", "-i", FilterGraphs.MIX_FILE,
                "-af", FilterGraphs.loudnormSecondPass(measured, plan.timeline().outputSamples()),
                "-c:a", "pcm_s24le", FilterGraphs.NORMALIZED_AUDIO)));
        var loudness = new Loudness(text(second, "normalization_type"), measured.inputI(), measured.inputTp(),
                text(second, "output_i"), text(second, "output_tp"));
        if (!List.of("linear", "dynamic").contains(loudness.normalizationType().toLowerCase(java.util.Locale.ROOT))) {
            throw MediaFailure.blocked("MEDIA_LOUDNESS_INVALID", "Unknown loudnorm normalisation type");
        }

        String videoGraph = FilterGraphs.video(plan);
        tools.write(FilterGraphs.VIDEO_GRAPH, videoGraph.getBytes(StandardCharsets.US_ASCII));
        List<String> renderArgs = new ArrayList<>(List.of("-hide_banner"));
        for (int i = 0; i < n; i++) {
            renderArgs.addAll(List.of("-i", FilterGraphs.image(i)));
        }
        renderArgs.addAll(List.of("-i", FilterGraphs.NORMALIZED_AUDIO, "-/filter_complex", FilterGraphs.VIDEO_GRAPH,
                "-map", "[vout]", "-map", n + ":a",
                "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-profile:v", "high", "-pix_fmt", "yuv420p",
                "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                "-r", "30", "-fps_mode", "cfr", "-g", "60",
                "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart", "-max_muxing_queue_size", "1024",
                "-metadata", "comment=" + comment(planSha, loudness), FilterGraphs.OUTPUT));
        tools.ffmpeg(Purpose.RENDER, renderArgs);

        var output = tools.file(FilterGraphs.OUTPUT);
        long bytes;
        try {
            bytes = Files.size(output);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_OUTPUT_MISSING", "Render output is missing", ex);
        }
        if (bytes < 1 || bytes > MAX_MP4 || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
            throw MediaFailure.blocked("MEDIA_RENDER_SIZE", "Render output size is outside 1 B..100 MiB");
        }
        String verifiedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("schemaVersion", 1);
        lineage.put("stage", ProductionStage.RENDER.name());
        lineage.put("renderer", VERSION);
        lineage.put("inputSha256", item.inputHash());
        lineage.put("planSha256", planSha);
        lineage.put("videoGraphSha256", FixtureCatalog.sha256(videoGraph.getBytes(StandardCharsets.US_ASCII)));
        lineage.put("audioGraphSha256", FixtureCatalog.sha256(audioGraph.getBytes(StandardCharsets.US_ASCII)));
        lineage.put("imageSha256", images.stream().map(s -> s.identity().sha256()).toList());
        lineage.put("pcmSha256", inputs.pcm().stream().map(s -> s.identity().sha256()).toList());
        lineage.put("assSha256", ass.identity().sha256());
        lineage.put("fontSha256", fonts.sha256());
        lineage.put("sounds", soundInputs);
        lineage.put("loudness", loudness);
        lineage.put("ffmpegSha256", support.settings().ffmpegSha256());
        List<Map<String, Object>> parents = new ArrayList<>();
        images.forEach(s -> parents.add(Map.of("role", Role.NORMALIZED_IMAGE.name(), "assetId", s.id(),
                "sha256", s.identity().sha256())));
        inputs.pcm().forEach(s -> parents.add(Map.of("role", Role.PCM_AUDIO.name(), "assetId", s.id(),
                "sha256", s.identity().sha256())));
        parents.add(Map.of("role", Role.ASS.name(), "assetId", ass.id(), "sha256", ass.identity().sha256()));
        soundInputs.forEach(s -> parents.add(Map.of("role", "SOUND", "sha256", s.get("sha256"))));
        String rights = support.encodeText(FixtureCatalog.rightsRecord(FixtureCatalog.derived(verifiedAt), "", parents));
        try {
            support.assets().publish(context, List.of(new Payload(Role.MP4, "video/mp4", MAX_MP4,
                    support.encodeText(lineage), rights, () -> Files.newInputStream(output, LinkOption.NOFOLLOW_LINKS))));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_PUBLISH_FAILED", "Render publication failed", ex);
        }
        return new Result(Outcome.APPLIED, null);
    }

    static String comment(String planSha, Loudness loudness) {
        return "aistudio;" + VERSION + ";plan=" + planSha + ";norm=" + loudness.normalizationType()
                + ";ii=" + loudness.inputI() + ";itp=" + loudness.inputTp();
    }

    private static FixtureCatalog.AudioFixture fixture(FixtureCatalog.Loaded catalog, String id) {
        return catalog.catalog().audio().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Audio fixture is missing"));
    }

    /** The last JSON object printed by loudnorm. */
    private JsonNode loudnorm(com.aistudio.production.contract.MediaProcessRunner.Result result) {
        String log = result.output() + "\n" + result.diagnosticTail();
        int end = log.lastIndexOf('}');
        int start = end < 0 ? -1 : log.lastIndexOf('{', end);
        if (start < 0) {
            throw MediaFailure.retry("MEDIA_LOUDNESS_INVALID", "loudnorm did not report a measurement", null);
        }
        return support.json().readTree(log.substring(start, end + 1));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString();
        if (value == null || value.isBlank() || value.length() > 16) {
            throw MediaFailure.blocked("MEDIA_LOUDNESS_INVALID", "loudnorm field " + field + " is missing");
        }
        return value.strip();
    }
}
