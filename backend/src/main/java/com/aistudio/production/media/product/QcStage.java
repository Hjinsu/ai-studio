package com.aistudio.production.media.product;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaAssets.Stored;
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
 * Technical QC (D07). Runs after the render on the staged MP4: ffprobe stream/format contract, faststart box order,
 * decoded frame count and CFR packet timing, full decode without errors, decoded AAC sample count equal to
 * frames x 1600, container A/V length, integrated loudness and true peak of the decoded AAC, narration length,
 * subtitle coverage/timing and the real libass glyph box, scene sample frames, sync evidence and rights. Any BLOCK
 * stops publication; the report and manifest are written only for a passing file, so the shared executor finalises
 * MP4, report and manifest together in the QC success transaction.
 */
@Component
class QcStage {
    private static final Logger log = LoggerFactory.getLogger(QcStage.class);
    static final String VERSION = "qc-1";
    static final String MP4 = "qc.mp4";
    private static final Pattern SUMMARY_I = Pattern.compile("I:\\s+(-?[0-9.]+) LUFS");
    private static final Pattern SUMMARY_LRA = Pattern.compile("LRA:\\s+(-?[0-9.]+) LU");
    private static final Pattern SUMMARY_PEAK = Pattern.compile("True peak:\\s+Peak:\\s+(-?[0-9.]+|-inf) dBFS");

    enum Verdict { PASS, WARN, BLOCK }

    record Check(String code, String target, Verdict result, String measured, String expected) {}

    private final StageSupport support;
    private final MetadataStage metadata;

    QcStage(StageSupport support, MetadataStage metadata) {
        this.support = support;
        this.metadata = metadata;
    }

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
        var srt = support.inputs(context, ProductionStage.SUBTITLE, Role.SRT, 1).getFirst();
        var metadataAsset = support.inputs(context, ProductionStage.METADATA, Role.MEDIA_METADATA, 1).getFirst();
        var video = support.inputs(context, ProductionStage.RENDER, Role.MP4, 1).getFirst();
        var fonts = SubtitleStage.fonts(support, catalog);
        var timeline = inputs.timeline();
        int frames = timeline.totalFrames();
        List<Check> checks = new ArrayList<>();

        // Metadata must describe exactly the plan that the current inputs produce.
        Map<String, Object> expectedMetadata = metadata.build(context, input, profile, catalog);
        JsonNode stored = support.json().readTree(new String(support.read(context, metadataAsset), StandardCharsets.UTF_8));
        String planSha = String.valueOf(expectedMetadata.get("planSha256"));
        check(checks, "METADATA_PLAN", "MEDIA_METADATA", planSha.equals(stored.path("planSha256").asString()),
                stored.path("planSha256").asString(), planSha);
        var rights = MetadataStage.rights(inputs.scenes(), profile, catalog,
                RenderPlan.of(inputs.scenes(), inputs.alignments(), timeline, catalog.catalog()), fonts);
        check(checks, "RIGHTS_COMPLETE", "ALL_SOURCES", true, rights.size() + " records", "complete, commercial use");

        var tools = MediaTools.open(support.runner(), new Scratch(context.owner().requestId(), item.id(),
                context.attemptNo()), context.cancelled());
        tools.copy(MP4, support.open(context, video));
        Path mp4 = tools.file(MP4);

        // Stream and container contract.
        JsonNode probe = support.json().readTree(tools.ffprobe(List.of("-v", "error", "-show_format", "-show_streams",
                "-of", "json", MP4)).output());
        JsonNode v = null;
        JsonNode a = null;
        int streams = 0;
        for (JsonNode s : probe.path("streams")) {
            streams++;
            if ("video".equals(s.path("codec_type").asString())) {
                v = s;
            } else if ("audio".equals(s.path("codec_type").asString())) {
                a = s;
            }
        }
        check(checks, "STREAMS", "MP4", streams == 2 && v != null && a != null, streams + " streams", "1 video + 1 audio");
        if (v == null || a == null) {
            return fail(checks);
        }
        check(checks, "VIDEO_CODEC", "MP4", "h264".equals(v.path("codec_name").asString())
                && "High".equals(v.path("profile").asString()), v.path("codec_name").asString() + "/"
                + v.path("profile").asString(), "h264/High");
        check(checks, "RESOLUTION", "MP4", v.path("width").asInt() == 1080 && v.path("height").asInt() == 1920,
                v.path("width").asInt() + "x" + v.path("height").asInt(), "1080x1920");
        check(checks, "PIXEL_FORMAT", "MP4", "yuv420p".equals(v.path("pix_fmt").asString()),
                v.path("pix_fmt").asString(), "yuv420p");
        String color = v.path("color_range").asString() + "/" + v.path("color_space").asString() + "/"
                + v.path("color_transfer").asString() + "/" + v.path("color_primaries").asString();
        check(checks, "COLOR", "MP4", "tv/bt709/bt709/bt709".equals(color), color, "tv/bt709/bt709/bt709");
        String rates = v.path("r_frame_rate").asString() + " " + v.path("avg_frame_rate").asString();
        check(checks, "FRAME_RATE", "MP4", "30/1 30/1".equals(rates), rates, "30/1 30/1 (CFR)");
        check(checks, "AUDIO_CODEC", "MP4", "aac".equals(a.path("codec_name").asString())
                && "LC".equals(a.path("profile").asString()), a.path("codec_name").asString() + "/"
                + a.path("profile").asString(), "aac/LC");
        check(checks, "AUDIO_FORMAT", "MP4", "48000".equals(a.path("sample_rate").asString())
                && a.path("channels").asInt() == 2, a.path("sample_rate").asString() + " Hz/" + a.path("channels").asInt()
                + " ch", "48000 Hz/2 ch");
        String format = probe.path("format").path("format_name").asString();
        check(checks, "CONTAINER", "MP4", format.contains("mp4"), format, "mov,mp4");
        String comment = probe.path("format").path("tags").path("comment").asString();
        check(checks, "RENDER_PLAN_BINDING", "MP4", comment != null && comment.contains("plan=" + planSha),
                comment, "plan=" + planSha);
        String normalization = field(comment, "norm");
        check(checks, "FASTSTART", "MP4", moovBeforeMdat(mp4), "moov before mdat", "moov before mdat");

        // Decoded frames, CFR packet timing and container lengths.
        long decodedFrames = support.json().readTree(tools.ffprobe(List.of("-v", "error", "-count_frames",
                "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "json", MP4)).output())
                .path("streams").path(0).path("nb_read_frames").asLong(-1);
        check(checks, "FRAME_COUNT", "MP4", decodedFrames == frames, Long.toString(decodedFrames), Integer.toString(frames));
        String packets = tools.ffprobe(List.of("-v", "error", "-select_streams", "v:0", "-show_entries",
                "packet=pts,duration", "-of", "csv=p=0", MP4)).output();
        check(checks, "CFR_PACKETS", "MP4", cfr(packets, v.path("time_base").asString(), frames),
                packetSummary(packets), "uniform 1/30 s packets");
        double vDuration = v.path("duration").asDouble(-1);
        double aDuration = a.path("duration").asDouble(-1);
        check(checks, "AV_LENGTH", "MP4", Math.abs(vDuration - aDuration) <= 1.0 / 30 + 1024.0 / 48_000,
                String.format(Locale.ROOT, "video %.6f s, audio %.6f s", vDuration, aDuration),
                "difference <= 1 frame + 1 AAC packet");

        // Full decode without errors.
        var decode = tools.ffmpeg(Purpose.DECODE_QC, List.of("-v", "error", "-xerror", "-i", MP4, "-map", "0",
                "-f", "null", "-"));
        String decodeLog = (decode.output() + decode.diagnosticTail()).strip();
        check(checks, "FULL_DECODE", "MP4", decodeLog.isEmpty(), decodeLog.isEmpty() ? "no errors"
                : truncate(decodeLog), "no decode errors");

        // Decoded AAC sample count and clipping.
        tools.delete("qc-audio.raw");
        tools.ffmpeg(Purpose.DECODE_QC, List.of("-v", "error", "-i", MP4, "-map", "0:a:0", "-c:a", "pcm_s16le",
                "-f", "s16le", "qc-audio.raw"));
        long rawBytes = size(tools.file("qc-audio.raw"));
        long samples = rawBytes / 4;
        check(checks, "AUDIO_SAMPLES", "MP4", rawBytes % 4 == 0 && samples == timeline.outputSamples(),
                Long.toString(samples), timeline.outputSamples() + " (frames x 1600)");
        long clipped = clipped(tools.file("qc-audio.raw"));
        verdict(checks, "CLIPPING", "MP4", clipped == 0 ? Verdict.PASS : clipped < 48 ? Verdict.WARN : Verdict.BLOCK,
                clipped + " clipped runs", "0");

        // Loudness of the decoded AAC.
        var loud = tools.ffmpeg(Purpose.LOUDNESS, List.of("-hide_banner", "-nostats", "-i", MP4, "-map", "0:a:0",
                "-af", "ebur128=peak=true:framelog=verbose", "-f", "null", "-"));
        String loudLog = loud.output() + loud.diagnosticTail();
        double integrated = number(SUMMARY_I, loudLog);
        double peak = number(SUMMARY_PEAK, loudLog);
        double lra = number(SUMMARY_LRA, loudLog);
        check(checks, "LOUDNESS", "MP4", integrated >= -18 && integrated <= -14,
                String.format(Locale.ROOT, "%.1f LUFS", integrated), "-18..-14 LUFS");
        check(checks, "TRUE_PEAK", "MP4", peak <= -1.0, String.format(Locale.ROOT, "%.1f dBTP", peak), "<= -1.0 dBTP");

        // Narration length and coverage on the decoded sample clock.
        double seconds = timeline.narrationSamples() / (double) Alignment.RATE;
        check(checks, "NARRATION_DURATION", "NARRATION", timeline.narrationInRange(),
                String.format(Locale.ROOT, "%.3f s", seconds), "40..60 s");
        check(checks, "VIDEO_COVERS_NARRATION", "TIMELINE", timeline.outputSamples() >= timeline.narrationSamples()
                && timeline.outputSamples() - timeline.narrationSamples() < Timeline.SAMPLES_PER_FRAME,
                timeline.outputSamples() + " >= " + timeline.narrationSamples(), "last sample covered, < 1 frame pad");
        boolean verified = inputs.alignments().stream().allMatch(al -> Alignment.VERIFIED.equals(al.timingSource()));
        check(checks, "SYNC_EVIDENCE", "ALIGNMENT", verified, verified ? "verified fixture timing" : "estimated",
                "verified alignment for every scene");
        long longest = 0;
        for (var al : inputs.alignments()) {
            for (var silence : al.silences()) {
                longest = Math.max(longest, silence.endSample() - silence.startSample());
            }
        }
        verdict(checks, "INTERNAL_SILENCE", "NARRATION", longest >= Alignment.SILENCE_BLOCK ? Verdict.BLOCK
                : longest >= Alignment.SILENCE_WARN ? Verdict.WARN : Verdict.PASS,
                String.format(Locale.ROOT, "%.2f s", longest / (double) Alignment.RATE), "< 1.2 s (block at 2.0 s)");

        // Subtitles: SRT coverage/timing against the alignment, ASS parity and the real glyph box.
        String srtText = new String(support.read(context, srt), StandardCharsets.UTF_8);
        String assText = new String(support.read(context, ass), StandardCharsets.UTF_8);
        subtitles(checks, srtText, assText, inputs, frames);
        tools.write(SubtitleStage.FONT_ALIAS + fonts.extension(), fonts.bytes());
        var box = measure(tools, assText, frames);
        check(checks, "SUBTITLE_SAFE_AREA", "ASS", box.inside(), box.toString(),
                "x " + SubtitleTrack.SAFE_LEFT + ".." + SubtitleTrack.SAFE_RIGHT + ", y " + SubtitleTrack.SAFE_TOP + ".."
                        + SubtitleTrack.SAFE_BOTTOM);

        // Scene sample frames: each scene's middle frame resembles its own image most.
        sceneSamples(checks, context, tools, images, timeline);

        boolean blocked = checks.stream().anyMatch(c -> c.result() == Verdict.BLOCK);
        if (blocked) {
            return fail(checks);
        }
        String verifiedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("checker", VERSION);
        report.put("mode", "FAKE");
        report.put("status", checks.stream().anyMatch(c -> c.result() == Verdict.WARN) ? "PASS_WITH_WARNINGS" : "PASS");
        report.put("checkedAt", verifiedAt);
        report.put("video", MetadataStage.asset(video));
        report.put("frames", frames);
        report.put("durationSeconds", frames / 30.0);
        report.put("narrationSeconds", seconds);
        report.put("integratedLufs", integrated);
        report.put("truePeakDbtp", peak);
        report.put("loudnessRangeLu", lra);
        report.put("normalizationType", normalization);
        report.put("subtitleGlyphBox", box);
        report.put("checks", checks);
        report.put("note", "Technical PASS means playable and contract-conformant. Content quality, monetisation and "
                + "human review are not evaluated in V2-04.");
        byte[] reportBytes = support.encode(report);
        String reportSha = FixtureCatalog.sha256(reportBytes);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("manifest", "media-manifest-1");
        manifest.put("mode", "FAKE");
        manifest.put("requestId", context.owner().requestId());
        manifest.put("channelId", context.channelId());
        manifest.put("contentId", context.contentId());
        manifest.put("runId", context.runId());
        manifest.put("revisionId", context.revisionId());
        manifest.put("profileSha256", context.profileHash());
        manifest.put("scriptSha256", input.scriptHash());
        manifest.put("scenesSha256", input.scenesHash());
        manifest.put("metadata", MetadataStage.asset(metadataAsset));
        manifest.put("media", stored);
        manifest.put("video", MetadataStage.asset(video));
        manifest.put("renderComment", comment);
        manifest.put("qcReportSha256", reportSha);
        manifest.put("qcStatus", report.get("status"));
        manifest.put("tools", Map.of("ffmpegSha256", support.settings().ffmpegSha256(),
                "ffprobeSha256", support.settings().ffprobeSha256(), "fontSha256", fonts.sha256(),
                "renderer", RenderStage.VERSION, "subtitle", SubtitleStage.VERSION, "qc", VERSION));
        byte[] manifestBytes = support.encode(manifest);
        List<Map<String, Object>> parents = List.of(Map.of("role", Role.MP4.name(), "assetId", video.id(),
                "sha256", video.identity().sha256()), Map.of("role", Role.MEDIA_METADATA.name(),
                "assetId", metadataAsset.id(), "sha256", metadataAsset.identity().sha256()));
        String rightsJson = support.encodeText(FixtureCatalog.rightsRecord(FixtureCatalog.derived(verifiedAt), "", parents));
        String lineage = support.encodeText(Map.of("schemaVersion", 1, "stage", "QC", "checker", VERSION,
                "inputSha256", item.inputHash(), "mp4Sha256", video.identity().sha256(),
                "metadataSha256", metadataAsset.identity().sha256()));
        try {
            support.assets().publish(context, List.of(
                    new Payload(Role.QC_REPORT, "application/json", reportBytes.length, lineage, rightsJson,
                            () -> new ByteArrayInputStream(reportBytes)),
                    new Payload(Role.MANIFEST, "application/json", manifestBytes.length, lineage, rightsJson,
                            () -> new ByteArrayInputStream(manifestBytes))));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_PUBLISH_FAILED", "QC publication failed", ex);
        }
        return new Result(Outcome.APPLIED, null);
    }

    private Result fail(List<Check> checks) {
        var first = checks.stream().filter(c -> c.result() == Verdict.BLOCK).findFirst().orElseThrow();
        for (var c : checks) {
            if (c.result() != Verdict.PASS) {
                log.warn("Media QC {} {} on {}: measured {} expected {}", c.result(), c.code(), c.target(),
                        c.measured(), c.expected());
            }
        }
        return new Result(Outcome.BLOCKED, "QC_" + first.code());
    }

    private static void check(List<Check> checks, String code, String target, boolean pass, String measured,
            String expected) {
        verdict(checks, code, target, pass ? Verdict.PASS : Verdict.BLOCK, measured, expected);
    }

    private static void verdict(List<Check> checks, String code, String target, Verdict verdict, String measured,
            String expected) {
        checks.add(new Check(code, target, verdict, measured == null ? "" : truncate(measured), expected));
    }

    private static String truncate(String text) {
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    static String field(String comment, String key) {
        if (comment == null) {
            return null;
        }
        for (String part : comment.split(";")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        return null;
    }

    /** Top-level MP4 boxes: moov must precede mdat (faststart). */
    static boolean moovBeforeMdat(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long position = 0;
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
            while (position + 8 <= size) {
                header.clear();
                channel.read(header, position);
                long length = Integer.toUnsignedLong(header.getInt(0));
                String type = new String(new byte[] {header.get(4), header.get(5), header.get(6), header.get(7)},
                        StandardCharsets.US_ASCII);
                if (length == 1) {
                    length = header.getLong(8);
                } else if (length == 0) {
                    length = size - position;
                }
                if ("moov".equals(type)) {
                    return true;
                }
                if ("mdat".equals(type) || length < 8) {
                    return false;
                }
                position += length;
            }
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Sorted presentation timestamps advance by exactly one 1/30 s step; the count equals the timeline. */
    static boolean cfr(String csv, String timeBase, int frames) {
        if (timeBase == null || !timeBase.matches("1/[0-9]+")) {
            return false;
        }
        long den = Long.parseLong(timeBase.substring(2));
        if (den % 30 != 0) {
            return false;
        }
        long step = den / 30;
        List<Long> pts = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 2 || !parts[0].matches("-?[0-9]+") || !parts[1].matches("[0-9]+")
                    || Long.parseLong(parts[1]) != step) {
                return false;
            }
            pts.add(Long.parseLong(parts[0]));
        }
        if (pts.size() != frames) {
            return false;
        }
        pts.sort(Long::compare);
        for (int i = 1; i < pts.size(); i++) {
            if (pts.get(i) - pts.get(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private static String packetSummary(String csv) {
        long lines = csv.lines().filter(line -> !line.isBlank()).count();
        String first = csv.lines().findFirst().orElse("");
        return lines + " packets, first " + first.strip();
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_OUTPUT_MISSING", "Decoded QC audio is missing", ex);
        }
    }

    /** Runs of 3+ consecutive full-scale samples in interleaved s16le stereo. */
    static long clipped(Path raw) {
        long runs = 0;
        try (InputStream in = Files.newInputStream(raw, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int[] streak = new int[2];
            int channel = 0;
            int carry = -1;
            for (int n; (n = in.read(buffer)) > 0;) {
                for (int i = 0; i < n; i++) {
                    if (carry < 0) {
                        carry = buffer[i] & 0xFF;
                        continue;
                    }
                    int sample = (short) (carry | (buffer[i] << 8));
                    carry = -1;
                    if (sample >= 32767 || sample <= -32768) {
                        if (++streak[channel] == 3) {
                            runs++;
                        }
                    } else {
                        streak[channel] = 0;
                    }
                    channel ^= 1;
                }
            }
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_OUTPUT_MISSING", "Decoded QC audio cannot be read", ex);
        }
        return runs;
    }

    private static double number(Pattern pattern, String log) {
        String summary = log.contains("Summary:") ? log.substring(log.lastIndexOf("Summary:")) : log;
        Matcher m = pattern.matcher(summary);
        if (!m.find()) {
            throw MediaFailure.retry("MEDIA_LOUDNESS_INVALID", "Loudness summary is missing", null);
        }
        return "-inf".equals(m.group(1)) ? Double.NEGATIVE_INFINITY : Double.parseDouble(m.group(1));
    }

    private void subtitles(List<Check> checks, String srt, String ass, SubtitleStage.Inputs inputs, int frames) {
        List<SubtitleTrack.ParsedCue> parsed;
        try {
            parsed = SubtitleTrack.parseSrt(srt);
        } catch (MediaFailure ex) {
            check(checks, "SRT_FORMAT", "SRT", false, ex.code(), "well-formed SRT");
            return;
        }
        List<Integer[]> expected = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int s = 0; s < inputs.alignments().size(); s++) {
            var span = inputs.timeline().scenes().get(s);
            for (var cue : inputs.alignments().get(s).cues()) {
                expected.add(new Integer[] {Timeline.frame(span.startSample() + cue.startSample()),
                        Timeline.frame(span.startSample() + cue.endSample()), span.endFrame()});
                texts.add(cue.text());
            }
        }
        boolean count = parsed.size() == expected.size();
        check(checks, "SUBTITLE_CUE_COUNT", "SRT", count, Integer.toString(parsed.size()), Integer.toString(expected.size()));
        if (!count) {
            return;
        }
        boolean text = true;
        boolean timing = true;
        boolean order = true;
        long previousEnd = 0;
        for (int i = 0; i < parsed.size(); i++) {
            var cue = parsed.get(i);
            var exp = expected.get(i);
            text &= cue.text().equals(texts.get(i));
            long startMs = Math.floorDiv(exp[0] * 2000L + 30, 60);
            long speechEndMs = Math.floorDiv(exp[1] * 2000L + 30, 60);
            long sceneEndMs = Math.floorDiv(exp[2] * 2000L + 30, 60);
            timing &= cue.startMs() == startMs && cue.endMs() >= speechEndMs && cue.endMs() <= sceneEndMs;
            order &= cue.startMs() >= previousEnd && cue.endMs() > cue.startMs()
                    && cue.endMs() <= Math.floorDiv(frames * 2000L + 30, 60);
            previousEnd = cue.endMs();
        }
        String joined = String.join(" ", parsed.stream().map(SubtitleTrack.ParsedCue::text).toList());
        String narration = String.join(" ", inputs.scenes().stream().map(s -> s.narration()).toList());
        check(checks, "SUBTITLE_TEXT_COVERAGE", "SRT", text && joined.equals(narration),
                joined.length() + " chars", narration.length() + " chars, exact narration");
        check(checks, "SUBTITLE_TIMING", "SRT", timing, timing ? "cue starts match alignment" : "mismatch",
                "start = aligned frame, end covers speech within scene");
        check(checks, "SUBTITLE_ORDER", "SRT", order, order ? "monotonic, non-overlapping" : "overlap or reversal",
                "monotonic, non-overlapping, inside video");
        long dialogues = ass.lines().filter(line -> line.startsWith("Dialogue: ")).count();
        check(checks, "ASS_PARITY", "ASS", dialogues == parsed.size() && ass.contains("PlayResX: 1080")
                && ass.contains("PlayResY: 1920"), dialogues + " dialogues", parsed.size() + " dialogues, 1080x1920");
    }

    /** Re-measures the stored ASS with its outline and shadow recoloured white. */
    private SubtitleStage.Box measure(MediaTools tools, String ass, int frames) {
        StringBuilder measured = new StringBuilder();
        for (String line : ass.split("\n", -1)) {
            if (line.startsWith("Style: Main,")) {
                String[] fields = line.split(",", -1);
                if (fields.length != 23) {
                    throw MediaFailure.blocked("QC_ASS_STYLE", "Unexpected ASS style line");
                }
                fields[5] = "&H00FFFFFF";
                fields[6] = "&H00FFFFFF";
                line = String.join(",", fields);
            }
            measured.append(line).append('\n');
        }
        tools.write("measure.ass", measured.toString().getBytes(StandardCharsets.UTF_8));
        tools.delete("bbox.txt");
        var result = tools.ffmpeg(Purpose.DECODE_QC, List.of("-hide_banner", "-f", "lavfi", "-i",
                "color=c=black:s=1080x1920:r=30:d=" + FilterGraphs.seconds(frames),
                "-vf", "ass=filename=measure.ass:fontsdir=fonts,bbox=min_val=96,metadata=mode=print:file=bbox.txt",
                "-f", "null", "-"));
        String text = result.output() + result.diagnosticTail();
        if (text.contains("Glyph 0x") || text.contains("failed to find any fallback")) {
            throw MediaFailure.blocked("QC_SUBTITLE_GLYPH_MISSING", "libass reported a missing glyph");
        }
        return SubtitleStage.parseBox(new String(tools.read("bbox.txt", 64L * 1024 * 1024), StandardCharsets.UTF_8));
    }

    private void sceneSamples(List<Check> checks, Context context, MediaTools tools, List<Stored> images,
            Timeline timeline) {
        int w = 54;
        int h = 96;
        StringBuilder select = new StringBuilder("select='");
        List<Integer> sampled = new ArrayList<>();
        for (var span : timeline.scenes()) {
            int frame = span.startFrame() + span.frames() / 2;
            sampled.add(frame);
            select.append(sampled.size() > 1 ? "+" : "").append("eq(n,").append(frame).append(")");
        }
        select.append("',scale=").append(w).append(':').append(h).append(":flags=area,format=gray");
        tools.delete("qc-frames.gray");
        tools.ffmpeg(Purpose.DECODE_QC, List.of("-v", "error", "-i", MP4, "-vf", select.toString(),
                "-fps_mode", "passthrough", "-f", "rawvideo", "qc-frames.gray"));
        byte[] raw = tools.read("qc-frames.gray", 16L * 1024 * 1024);
        int count = raw.length / (w * h);
        if (count != sampled.size()) {
            verdict(checks, "SCENE_SAMPLE_FRAMES", "MP4", Verdict.BLOCK, count + " frames", sampled.size() + " frames");
            return;
        }
        List<double[]> references = new ArrayList<>();
        for (Stored image : images) {
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(support.read(context, image)));
                references.add(gray(ImageNormalizer.resize(decoded, w, h), w, h));
            } catch (IOException ex) {
                throw MediaFailure.blocked("QC_IMAGE_INVALID", "Normalised image cannot be decoded");
            }
        }
        int matched = 0;
        List<String> scores = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double[] frame = new double[w * h];
            for (int p = 0; p < w * h; p++) {
                frame[p] = raw[i * w * h + p] & 0xFF;
            }
            int best = -1;
            double bestScore = -2;
            for (int r = 0; r < references.size(); r++) {
                double score = correlation(frame, references.get(r), w, (int) (h * 0.70));
                if (score > bestScore) {
                    bestScore = score;
                    best = r;
                }
            }
            if (best == i) {
                matched++;
            }
            scores.add(String.format(Locale.ROOT, "%d->%d(%.2f)", i + 1, best + 1, bestScore));
        }
        verdict(checks, "SCENE_SAMPLE_FRAMES", "MP4", matched == count ? Verdict.PASS : Verdict.WARN,
                String.join(" ", scores), "each scene's middle frame matches its own image");
    }

    private static double[] gray(BufferedImage image, int w, int h) {
        double[] out = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                out[y * w + x] = 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
            }
        }
        return out;
    }

    /** Pearson correlation over the top {@code rows} rows (the subtitle band is excluded). */
    static double correlation(double[] a, double[] b, int w, int rows) {
        int n = w * rows;
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= n;
        mb /= n;
        double num = 0;
        double da = 0;
        double db = 0;
        for (int i = 0; i < n; i++) {
            num += (a[i] - ma) * (b[i] - mb);
            da += (a[i] - ma) * (a[i] - ma);
            db += (b[i] - mb) * (b[i] - mb);
        }
        return da == 0 || db == 0 ? 0 : num / Math.sqrt(da * db);
    }
}
