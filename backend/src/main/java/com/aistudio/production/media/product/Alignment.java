package com.aistudio.production.media.product;

import java.util.ArrayList;
import java.util.List;

import com.aistudio.production.contract.ProductionPlanning;

/**
 * Versioned per-scene alignment on the 48 kHz sample clock (D02/D03). Cue text is an exact slice of the segment
 * narration; cue ranges are start-inclusive/end-exclusive sample offsets into the scene PCM. {@code timingSource}
 * distinguishes a verified fixture timetable from an estimate; only verified timing may pass the sync check.
 */
record Alignment(int schemaVersion, String sceneId, int sceneOrder, String narrationSha256, int sampleRate,
        long sampleCount, String timingSource, String precision, String fixtureId, String sourceSha256,
        String pcmSha256, List<Segment> segments, List<String> emphasis, List<Silence> silences,
        List<String> warnings) {
    static final int SCHEMA = 1;
    static final int RATE = 48_000;
    static final String VERIFIED = "VERIFIED_FIXTURE";
    static final long SILENCE_WARN = 57_600;   // 1.2 s
    static final long SILENCE_BLOCK = 96_000;  // 2.0 s

    record Cue(String text, long startSample, long endSample) {}

    record Segment(String segmentId, long startSample, long endSample, List<Cue> cues) {}

    record Silence(long startSample, long endSample) {}

    Alignment {
        segments = List.copyOf(segments);
        emphasis = List.copyOf(emphasis);
        silences = List.copyOf(silences);
        warnings = List.copyOf(warnings);
    }

    /**
     * Converts a fixture timetable to the 48 kHz clock and verifies it against the decoded audio: segment IDs and
     * order equal the scene, each cue is a non-empty exact slice and the slices joined by single spaces reproduce
     * the segment narration, ranges are positive, ordered, non-overlapping and inside the audio.
     */
    static Alignment build(ProductionPlanning.Scene scene, java.util.Map<String, String> scriptNarration,
            FixtureCatalog.NarrationFixture fixture, String sourceSha256, short[] pcm48, String pcmSha256) {
        if (fixture.segments().size() != scene.segmentIds().size()) {
            throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Fixture segments do not match the scene");
        }
        long total = pcm48.length;
        List<Segment> segments = new ArrayList<>();
        long previousEnd = 0;
        for (int i = 0; i < scene.segmentIds().size(); i++) {
            String id = scene.segmentIds().get(i);
            var timing = fixture.segments().get(i);
            String narration = scriptNarration.get(id);
            if (!id.equals(timing.segmentId()) || narration == null || timing.cues().isEmpty()) {
                throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Segment order or text mismatch");
            }
            List<Cue> cues = new ArrayList<>();
            StringBuilder joined = new StringBuilder();
            for (var cue : timing.cues()) {
                if (cue.text() == null || cue.text().isBlank() || !cue.text().equals(cue.text().strip())
                        || cue.text().chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                    throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Invalid cue text");
                }
                long start = Resampler.map(cue.startSample(), fixture.sampleRate(), RATE);
                long end = Resampler.map(cue.endSample(), fixture.sampleRate(), RATE);
                if (cue.startSample() < 0 || end <= start || start < previousEnd || end > total) {
                    throw MediaFailure.blocked("MEDIA_ALIGNMENT_RANGE", "Cue timing is outside the decoded audio");
                }
                previousEnd = end;
                if (!joined.isEmpty()) {
                    joined.append(' ');
                }
                joined.append(cue.text());
                cues.add(new Cue(cue.text(), start, end));
            }
            if (!joined.toString().equals(narration)) {
                throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Cue text does not reproduce the script");
            }
            segments.add(new Segment(id, cues.getFirst().startSample(), cues.getLast().endSample(), cues));
        }
        List<String> emphasis = fixture.emphasis() == null ? List.of() : fixture.emphasis();
        for (String phrase : emphasis) {
            if (phrase.isBlank() || !scene.narration().contains(phrase)) {
                throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Emphasis phrase is not in the narration");
            }
        }
        List<String> warnings = new ArrayList<>();
        List<Silence> silences = silences(pcm48, segments.getFirst().startSample(), segments.getLast().endSample());
        for (Silence silence : silences) {
            long length = silence.endSample() - silence.startSample();
            if (length >= SILENCE_BLOCK) {
                throw MediaFailure.blocked("MEDIA_NARRATION_SILENCE", "Internal silence of 2 s or more");
            }
            if (length >= SILENCE_WARN) {
                warnings.add("INTERNAL_SILENCE_ABOVE_1_2S");
            }
        }
        if (!VERIFIED.equals(fixture.timingSource())) {
            warnings.add("TIMING_NOT_VERIFIED");
        }
        return new Alignment(SCHEMA, scene.id(), scene.order(), FixtureCatalog.sha256(
                scene.narration().getBytes(java.nio.charset.StandardCharsets.UTF_8)), RATE, total,
                VERIFIED.equals(fixture.timingSource()) ? VERIFIED : "ESTIMATED", "CUE", fixture.id(), sourceSha256,
                pcmSha256, segments, emphasis, silences, warnings);
    }

    /** Silences below -40 dBFS lasting at least 0.7 s, inside [from, to), from 10 ms RMS windows. */
    static List<Silence> silences(short[] pcm, long from, long to) {
        int window = RATE / 100;
        double threshold = 32768 * Math.pow(10, -40 / 20.0);
        List<Silence> out = new ArrayList<>();
        long runStart = -1;
        for (long start = from; start + window <= to; start += window) {
            double sum = 0;
            for (int i = 0; i < window; i++) {
                double v = pcm[(int) (start + i)];
                sum += v * v;
            }
            boolean quiet = Math.sqrt(sum / window) < threshold;
            if (quiet && runStart < 0) {
                runStart = start;
            } else if (!quiet && runStart >= 0) {
                if (start - runStart >= 33_600) {
                    out.add(new Silence(runStart, start));
                }
                runStart = -1;
            }
        }
        if (runStart >= 0 && to - runStart >= 33_600) {
            out.add(new Silence(runStart, to));
        }
        return out;
    }

    /** Re-verifies a stored alignment against its scene and PCM (used by later steps and QC). */
    void verify(ProductionPlanning.Scene scene, long pcmSamples) {
        if (schemaVersion != SCHEMA || sampleRate != RATE || sampleCount != pcmSamples || !sceneId.equals(scene.id())
                || segments.size() != scene.segmentIds().size()) {
            throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Alignment does not match its scene audio");
        }
        StringBuilder text = new StringBuilder();
        long previous = 0;
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            if (!segment.segmentId().equals(scene.segmentIds().get(i))) {
                throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Alignment segment order changed");
            }
            for (Cue cue : segment.cues()) {
                if (cue.startSample() < previous || cue.endSample() <= cue.startSample() || cue.endSample() > sampleCount) {
                    throw MediaFailure.blocked("MEDIA_ALIGNMENT_RANGE", "Alignment cue range is invalid");
                }
                previous = cue.endSample();
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(cue.text());
            }
        }
        if (!text.toString().equals(scene.narration())) {
            throw MediaFailure.blocked("MEDIA_ALIGNMENT_MISMATCH", "Alignment text does not cover the narration");
        }
    }

    List<Cue> cues() {
        return segments.stream().flatMap(segment -> segment.cues().stream()).toList();
    }
}
