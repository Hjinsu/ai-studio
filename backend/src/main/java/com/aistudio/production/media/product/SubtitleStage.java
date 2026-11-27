package com.aistudio.production.media.product;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaAssets.Stored;
import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaProcessRunner.Purpose;
import com.aistudio.production.contract.MediaProcessRunner.Scratch;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStageWork.Result;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;

/**
 * SUBTITLE (D03): builds ASS (burn-in) and SRT (review/download) from the verified alignments, then renders the
 * ASS with the pinned static font through libass on a blank 1080x1920 canvas and checks the real glyph+outline
 * bounding box against the safe area. The size steps down from 76 px to 60 px; if it still does not fit, the step is
 * blocked. Missing glyphs or font fallback also block.
 */
@Component
class SubtitleStage {
    static final String VERSION = "subtitle-1";
    static final String FONT_ALIAS = "fonts/subtitle-font";

    record Box(int x1, int y1, int x2, int y2) {
        boolean inside() {
            return x1 >= SubtitleTrack.SAFE_LEFT && x2 <= SubtitleTrack.SAFE_RIGHT && y1 >= SubtitleTrack.SAFE_TOP
                    && y2 <= SubtitleTrack.SAFE_BOTTOM;
        }
    }

    record Inputs(List<ProductionPlanning.Scene> scenes, List<Stored> pcm, List<Stored> alignmentAssets,
            List<Alignment> alignments, Timeline timeline) {}

    private final StageSupport support;

    SubtitleStage(StageSupport support) {
        this.support = support;
    }

    Result run(Context context) {
        var item = context.items().getFirst();
        if ("APPLIED".equals(item.state())) {
            return new Result(Outcome.APPLIED, null);
        }
        var input = support.input(context, item);
        var profile = support.profile(context);
        var catalog = support.catalog(profile);
        Inputs inputs = inputs(support, context, input);
        Fonts fonts = fonts(support, catalog);
        var tools = MediaTools.open(support.runner(), new Scratch(context.owner().requestId(), item.id(),
                context.attemptNo()), context.cancelled());
        tools.write(FONT_ALIAS + fonts.extension(), fonts.bytes());
        SubtitleTrack.Built built = null;
        Box box = null;
        List<Integer> tried = new ArrayList<>();
        for (int size = SubtitleTrack.DEFAULT_SIZE; size >= SubtitleTrack.MIN_SIZE; size -= 4) {
            tried.add(size);
            try {
                built = SubtitleTrack.build(inputs.alignments(), inputs.timeline(), fonts.font(),
                        support.settings().fontFamily(), size);
            } catch (MediaFailure ex) {
                if (!"SUBTITLE_TOO_LONG".equals(ex.code()) || size - 4 < SubtitleTrack.MIN_SIZE) {
                    throw ex;
                }
                continue;
            }
            box = measure(tools, built.track(), inputs.timeline().totalFrames());
            if (box.inside()) {
                break;
            }
        }
        if (built == null || box == null || !box.inside()) {
            throw MediaFailure.blocked("SUBTITLE_SAFE_AREA", "Subtitles exceed the safe area at the minimum size");
        }
        var track = built.track();
        String verifiedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("schemaVersion", 1);
        lineage.put("stage", ProductionStage.SUBTITLE.name());
        lineage.put("generator", VERSION);
        lineage.put("inputSha256", item.inputHash());
        lineage.put("alignmentSha256", inputs.alignmentAssets().stream().map(s -> s.identity().sha256()).toList());
        lineage.put("pcmSha256", inputs.pcm().stream().map(s -> s.identity().sha256()).toList());
        lineage.put("fontSha256", fonts.sha256());
        lineage.put("fontFamily", support.settings().fontFamily());
        lineage.put("fontSize", track.fontSize());
        lineage.put("sizesTried", tried);
        lineage.put("cueCount", track.cues().size());
        lineage.put("totalFrames", inputs.timeline().totalFrames());
        lineage.put("glyphBox", box);
        lineage.put("warnings", built.warnings());
        List<Map<String, Object>> parents = new ArrayList<>();
        inputs.alignmentAssets().forEach(s -> parents.add(Map.of("role", Role.ALIGNMENT.name(), "assetId", s.id(),
                "sha256", s.identity().sha256())));
        parents.add(Map.of("role", "FONT", "sha256", fonts.sha256()));
        String rights = support.encodeText(FixtureCatalog.rightsRecord(FixtureCatalog.derived(verifiedAt), "", parents));
        byte[] ass = track.ass(false).getBytes(StandardCharsets.UTF_8);
        byte[] srt = track.srt().getBytes(StandardCharsets.UTF_8);
        String lineageJson = support.encodeText(lineage);
        try {
            support.assets().publish(context, List.of(
                    new Payload(Role.ASS, "text/x-ssa", ass.length, lineageJson, rights, () -> new ByteArrayInputStream(ass)),
                    new Payload(Role.SRT, "application/x-subrip", srt.length, lineageJson, rights,
                            () -> new ByteArrayInputStream(srt))));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_PUBLISH_FAILED", "Subtitle publication failed", ex);
        }
        return new Result(Outcome.APPLIED, null);
    }

    /** Scenes, PCM and alignments of all scenes in order, each alignment re-verified against its scene and audio. */
    static Inputs inputs(StageSupport support, Context context, StageSupport.ItemInput input) {
        var scenes = support.scenes(context, input);
        int n = scenes.size();
        var pcm = support.inputs(context, ProductionStage.TTS, Role.PCM_AUDIO, n);
        var alignmentAssets = support.inputs(context, ProductionStage.TTS, Role.ALIGNMENT, n);
        List<Alignment> alignments = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Wav wav = Wav.parse(support.read(context, pcm.get(i)));
            if (wav.sampleRate() != Alignment.RATE || wav.channels() != 1) {
                throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Narration PCM is not 48 kHz mono");
            }
            Alignment alignment = support.alignment(context, alignmentAssets.get(i));
            alignment.verify(scenes.get(i), wav.frames());
            if (!alignment.pcmSha256().equals(pcm.get(i).identity().sha256())) {
                throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Alignment refers to different audio");
            }
            alignments.add(alignment);
            counts.add(wav.frames());
        }
        var timeline = Timeline.of(scenes.stream().map(ProductionPlanning.Scene::id).toList(), counts);
        return new Inputs(scenes, pcm, alignmentAssets, alignments, timeline);
    }

    record Fonts(byte[] bytes, String sha256, String extension, Font font, FixtureCatalog.FontFixture fixture) {}

    /** The pinned static subtitle font, verified by SHA-256 and backed by a catalog rights record. */
    static Fonts fonts(StageSupport support, FixtureCatalog.Loaded catalog) {
        var settings = support.settings();
        if (settings.fontPath().isBlank() || !settings.fontSha256().matches("[0-9a-f]{64}")) {
            throw MediaFailure.blocked("SUBTITLE_FONT_UNAVAILABLE", "Subtitle font is not configured");
        }
        byte[] bytes;
        try {
            bytes = FixtureCatalog.readBounded(Path.of(settings.fontPath()), 32L * 1024 * 1024);
        } catch (IOException ex) {
            throw MediaFailure.blocked("SUBTITLE_FONT_UNAVAILABLE", "Subtitle font cannot be read");
        }
        String sha = FixtureCatalog.sha256(bytes);
        if (!sha.equals(settings.fontSha256())) {
            throw MediaFailure.blocked("SUBTITLE_FONT_INTEGRITY", "Subtitle font hash mismatch");
        }
        var fixture = catalog.catalog().font(sha).orElseThrow(() -> MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE",
                "Subtitle font has no rights record"));
        if (!support.settings().fontFamily().equals(fixture.family())) {
            throw MediaFailure.blocked("SUBTITLE_FONT_INTEGRITY", "Configured font family differs from the catalog");
        }
        String name = settings.fontPath().toLowerCase(java.util.Locale.ROOT);
        String extension = name.endsWith(".otf") ? ".otf" : name.endsWith(".ttf") ? ".ttf" : null;
        if (extension == null) {
            throw MediaFailure.blocked("SUBTITLE_FONT_UNAVAILABLE", "Subtitle font must be a static TTF or OTF file");
        }
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
            return new Fonts(bytes, sha, extension, font, fixture);
        } catch (FontFormatException | IOException ex) {
            throw MediaFailure.blocked("SUBTITLE_FONT_UNAVAILABLE", "Subtitle font cannot be parsed");
        }
    }

    /**
     * Renders the measurement variant of the ASS (white outline and shadow) over black at 30 fps and returns the
     * union of the per-frame bounding boxes. libass must select the pinned family without fallback or missing glyphs.
     */
    static Box measure(MediaTools tools, SubtitleTrack track, int totalFrames) {
        tools.write("measure.ass", track.ass(true).getBytes(StandardCharsets.UTF_8));
        tools.delete("bbox.txt");
        var result = tools.ffmpeg(Purpose.DECODE_QC, List.of("-hide_banner", "-f", "lavfi", "-i",
                "color=c=black:s=1080x1920:r=30:d=" + FilterGraphs.seconds(totalFrames),
                "-vf", "ass=filename=measure.ass:fontsdir=fonts,bbox=min_val=96,metadata=mode=print:file=bbox.txt",
                "-f", "null", "-"));
        String log = result.output() + result.diagnosticTail();
        if (log.contains("Glyph 0x") || log.contains("failed to find any fallback") || log.contains("No font")) {
            throw MediaFailure.blocked("SUBTITLE_GLYPH_MISSING", "libass reported a missing glyph or font fallback");
        }
        return parseBox(new String(tools.read("bbox.txt", 64L * 1024 * 1024), StandardCharsets.UTF_8));
    }

    /** Union of the {@code lavfi.bbox.*} values printed per frame by the metadata filter. */
    static Box parseBox(String bbox) {
        int x1 = Integer.MAX_VALUE;
        int y1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y2 = Integer.MIN_VALUE;
        for (String line : bbox.split("\n")) {
            line = line.strip();
            int eq = line.indexOf('=');
            if (!line.startsWith("lavfi.bbox.") || eq < 0) {
                continue;
            }
            int value = Integer.parseInt(line.substring(eq + 1).strip());
            switch (line.substring(0, eq)) {
                case "lavfi.bbox.x1" -> x1 = Math.min(x1, value);
                case "lavfi.bbox.y1" -> y1 = Math.min(y1, value);
                case "lavfi.bbox.x2" -> x2 = Math.max(x2, value);
                case "lavfi.bbox.y2" -> y2 = Math.max(y2, value);
                default -> { }
            }
        }
        if (x1 == Integer.MAX_VALUE) {
            throw MediaFailure.blocked("SUBTITLE_NOT_RENDERED", "No subtitle pixels were rendered");
        }
        return new Box(x1, y1, x2, y2);
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Scratch is reclaimed by the shared cleanup policy.
        }
    }
}
