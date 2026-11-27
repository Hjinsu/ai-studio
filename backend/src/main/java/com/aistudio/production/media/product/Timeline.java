package com.aistudio.production.media.product;

import java.util.ArrayList;
import java.util.List;

/**
 * Single ordered timeline on the 48 kHz sample clock (D02). Scene audio is concatenated without trimming; scene
 * boundaries are cumulative samples rounded to the nearest 30 fps frame (1600 samples) and the video length is
 * rounded up so the last narration sample is covered. Transitions overlap video handles only.
 */
record Timeline(List<Span> scenes, long narrationSamples, int totalFrames) {
    static final int FPS = 30;
    static final int SAMPLES_PER_FRAME = 1600;
    static final long MIN_NARRATION = 40L * Alignment.RATE;
    static final long MAX_NARRATION = 60L * Alignment.RATE;

    record Span(int index, String sceneId, long startSample, long sampleCount, int startFrame, int endFrame) {
        int frames() {
            return endFrame - startFrame;
        }
    }

    Timeline {
        scenes = List.copyOf(scenes);
    }

    static Timeline of(List<String> sceneIds, List<Long> sampleCounts) {
        if (sceneIds.isEmpty() || sceneIds.size() != sampleCounts.size() || sceneIds.size() > 20) {
            throw MediaFailure.blocked("MEDIA_TIMELINE_INVALID", "Scene audio set is incomplete");
        }
        long total = 0;
        List<Long> starts = new ArrayList<>();
        for (long count : sampleCounts) {
            if (count <= 0) {
                throw MediaFailure.blocked("MEDIA_TIMELINE_INVALID", "Scene audio is empty");
            }
            starts.add(total);
            total += count;
        }
        int frames = (int) ((total + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME);
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < sceneIds.size(); i++) {
            int start = i == 0 ? 0 : frame(starts.get(i));
            int end = i + 1 < sceneIds.size() ? frame(starts.get(i + 1)) : frames;
            if (end <= start) {
                throw MediaFailure.blocked("MEDIA_TIMELINE_INVALID", "Scene is shorter than one frame");
            }
            spans.add(new Span(i, sceneIds.get(i), starts.get(i), sampleCounts.get(i), start, end));
        }
        return new Timeline(spans, total, frames);
    }

    /** Nearest frame for a global sample offset. */
    static int frame(long sample) {
        return (int) Math.floorDiv(sample + SAMPLES_PER_FRAME / 2, SAMPLES_PER_FRAME);
    }

    long outputSamples() {
        return (long) totalFrames * SAMPLES_PER_FRAME;
    }

    boolean narrationInRange() {
        return narrationSamples >= MIN_NARRATION && narrationSamples <= MAX_NARRATION;
    }
}
