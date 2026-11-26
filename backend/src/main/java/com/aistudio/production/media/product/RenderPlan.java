package com.aistudio.production.media.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.aistudio.production.contract.ProductionPlanning;

/**
 * Deterministic render plan (D04): per-scene camera preset, transition, punch frame and sound placements computed
 * only from the frozen SCENES text, the verified timeline and the fixture catalog. Free-text direction and transition
 * are mapped to allow-listed enums; nothing from the script is ever placed into a filter graph or argument.
 */
record RenderPlan(int version, Timeline timeline, List<Shot> shots, List<Sound> sounds, String musicId,
        double musicGainDb, boolean fadeOut, List<String> diagnostics) {
    static final int VERSION = 1;
    static final int FADE_FRAMES = 15;
    static final int SHORT_SCENE_FRAMES = 2 * FADE_FRAMES;

    enum Camera { STATIC, ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN, PUNCH_IN }

    enum Transition { CUT, FADE }

    /** transitionOut applies between this shot and the next one; punchFrame is relative to the shot start. */
    record Shot(int index, String sceneId, int startFrame, int frames, Camera camera, Transition transitionOut,
            int punchFrame, double maxZoom) {}

    /** A sound effect starting at a global sample offset. */
    record Sound(String fixtureId, String kind, long startSample, double gainDb) {}

    RenderPlan {
        shots = List.copyOf(shots);
        sounds = List.copyOf(sounds);
        diagnostics = List.copyOf(diagnostics);
    }

    static RenderPlan of(List<ProductionPlanning.Scene> scenes, List<Alignment> alignments, Timeline timeline,
            FixtureCatalog catalog) {
        if (scenes.size() != timeline.scenes().size() || alignments.size() != scenes.size()) {
            throw MediaFailure.blocked("MEDIA_TIMELINE_INVALID", "Scenes and timeline differ");
        }
        List<String> diagnostics = new ArrayList<>();
        List<Shot> shots = new ArrayList<>();
        List<Sound> sounds = new ArrayList<>();
        var hook = catalog.audio("SFX_HOOK");
        var whoosh = catalog.audio("SFX_TRANSITION");
        var hit = catalog.audio("SFX_PUNCH");
        for (int i = 0; i < scenes.size(); i++) {
            var scene = scenes.get(i);
            var span = timeline.scenes().get(i);
            Camera camera = camera(scene.direction());
            if (camera == null) {
                camera = i % 2 == 0 ? Camera.ZOOM_IN : Camera.ZOOM_OUT;
                diagnostics.add("DIRECTION_UNMAPPED:" + scene.id());
            }
            Transition out = Transition.CUT;
            boolean last = i == scenes.size() - 1;
            if (!last) {
                out = transition(scene.transition());
                var next = timeline.scenes().get(i + 1);
                if (out == Transition.FADE && (span.frames() < SHORT_SCENE_FRAMES || next.frames() < SHORT_SCENE_FRAMES)) {
                    out = Transition.CUT;
                    diagnostics.add("SHORT_SCENE_CUT:" + scene.id());
                }
            }
            int punch = -1;
            if (camera == Camera.PUNCH_IN) {
                var alignment = alignments.get(i);
                long at = alignment.segments().size() > 1 ? alignment.segments().getLast().startSample()
                        : alignment.sampleCount() * 2 / 5;
                punch = Math.max(1, Math.min(span.frames() - 6, Timeline.frame(span.startSample() + at) - span.startFrame()));
                if (hit.isPresent()) {
                    sounds.add(new Sound(hit.get().id(), hit.get().kind(),
                            (long) (span.startFrame() + punch) * Timeline.SAMPLES_PER_FRAME, hit.get().gainDb()));
                }
            }
            if (i == 0 && hook.isPresent()) {
                sounds.add(new Sound(hook.get().id(), hook.get().kind(), 0, hook.get().gainDb()));
            }
            if (i > 0 && whoosh.isPresent() && shots.get(i - 1).transitionOut() == Transition.FADE) {
                long at = Math.max(0, (long) (span.startFrame() - 6) * Timeline.SAMPLES_PER_FRAME);
                sounds.add(new Sound(whoosh.get().id(), whoosh.get().kind(), at, whoosh.get().gainDb()));
            }
            shots.add(new Shot(i, scene.id(), span.startFrame(), span.frames(), camera, out, punch, maxZoom(camera)));
        }
        boolean fadeOut = scenes.getLast().transition() != null
                && scenes.getLast().transition().toLowerCase(Locale.ROOT).contains("fade")
                && timeline.scenes().getLast().frames() >= 2 * SHORT_SCENE_FRAMES;
        var music = catalog.audio("MUSIC");
        return new RenderPlan(VERSION, timeline, shots, sounds, music.map(FixtureCatalog.AudioFixture::id).orElse(null),
                music.map(FixtureCatalog.AudioFixture::gainDb).orElse(0.0), fadeOut, diagnostics);
    }

    static Camera camera(String direction) {
        String d = direction == null ? "" : direction.toLowerCase(Locale.ROOT);
        if (has(d, "punch", "shake", "snap zoom", "quick zoom", "crash zoom")) {
            return Camera.PUNCH_IN;
        }
        if (has(d, "pull back", "pulls back", "pull-back", "zoom out", "dolly out", "pulls out")) {
            return Camera.ZOOM_OUT;
        }
        if (has(d, "push-in", "push in", "pushes in", "zoom in", "zoom toward", "dolly in", "close in")) {
            return Camera.ZOOM_IN;
        }
        if (has(d, "static", "locked")) {
            return Camera.STATIC;
        }
        if (has(d, "tilt up", "upward tilt", "low angle", "crane up")) {
            return Camera.PAN_UP;
        }
        if (has(d, "tilt down", "downward tilt", "high angle", "crane down")) {
            return Camera.PAN_DOWN;
        }
        if (has(d, "pan left", "to the left")) {
            return Camera.PAN_LEFT;
        }
        if (has(d, "pan right", "to the right", "orbit", "tracking")) {
            return Camera.PAN_RIGHT;
        }
        return null;
    }

    static Transition transition(String transition) {
        String t = transition == null ? "" : transition.toLowerCase(Locale.ROOT);
        if (has(t, "fade", "dissolve", "wipe", "flash", "slide", "rack focus", "whip")) {
            return Transition.FADE;
        }
        return Transition.CUT;
    }

    static double maxZoom(Camera camera) {
        return switch (camera) {
            case STATIC -> 1.0;
            case ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN -> 1.12;
            case PUNCH_IN -> 1.18;
        };
    }

    /** The largest zoom any camera preset can apply; used to bound the image upscale before the shot is known. */
    static final double MAX_ZOOM = 1.18;

    private static boolean has(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Frames each shot's clip must render: its span plus a fade handle when it fades into the next shot. */
    int clipFrames(Shot shot) {
        return shot.frames() + (shot.transitionOut() == Transition.FADE ? FADE_FRAMES : 0);
    }
}
