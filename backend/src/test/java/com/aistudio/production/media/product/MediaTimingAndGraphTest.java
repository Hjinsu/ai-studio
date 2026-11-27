package com.aistudio.production.media.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Font;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning;

/** D02-D04: alignment verification, frame quantisation, ASS/SRT safety, camera/transition mapping, filter graphs. */
class MediaTimingAndGraphTest {
    static final FixtureCatalog.Rights RIGHTS = new FixtureCatalog.Rights("self", "AI_STUDIO_SELF_MADE", "own work",
            "ref", "tester", "2026-11-27T00:00:00Z", true, true, true, "");

    static ProductionPlanning.Scene scene(String id, int order, List<String> segments, String narration,
            String direction, String transition) {
        return new ProductionPlanning.Scene(id, order, segments, 4000, narration, "visual", "prompt " + id, direction,
                transition, narration);
    }

    static FixtureCatalog.NarrationFixture fixture(List<FixtureCatalog.SegmentTiming> segments) {
        return new FixtureCatalog.NarrationFixture("n", "voice", "en", "x", "audio/a.wav", "0".repeat(64), 1, 48_000,
                Alignment.VERIFIED, segments, List.of(), RIGHTS);
    }

    static short[] speech(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (8000 * Math.sin(i / 7.0));
        }
        return pcm;
    }

    @Test
    void alignmentReproducesTheScriptTextAndStaysInsideTheAudio() {
        var scene = scene("scene-1", 1, List.of("s1", "s2"), "A big claim. Then a twist.", "", "");
        var timing = List.of(new FixtureCatalog.SegmentTiming("s1", List.of(new FixtureCatalog.Cue("A big claim.", 0, 40_000))),
                new FixtureCatalog.SegmentTiming("s2", List.of(new FixtureCatalog.Cue("Then", 50_000, 60_000),
                        new FixtureCatalog.Cue("a twist.", 60_000, 90_000))));
        var script = Map.of("s1", "A big claim.", "s2", "Then a twist.");
        var alignment = Alignment.build(scene, script, fixture(timing), "a".repeat(64), speech(96_000), "b".repeat(64));
        assertThat(alignment.timingSource()).isEqualTo(Alignment.VERIFIED);
        assertThat(alignment.cues()).hasSize(3);
        alignment.verify(scene, 96_000);

        var wrongText = List.of(new FixtureCatalog.SegmentTiming("s1", List.of(new FixtureCatalog.Cue("A huge claim.", 0, 40_000))),
                timing.get(1));
        assertThatThrownBy(() -> Alignment.build(scene, script, fixture(wrongText), "a".repeat(64), speech(96_000), "b"))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_ALIGNMENT_MISMATCH"));
        assertThatThrownBy(() -> Alignment.build(scene, script, fixture(timing), "a".repeat(64), speech(80_000), "b"))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_ALIGNMENT_RANGE"));
        var reordered = List.of(timing.get(1), timing.get(0));
        assertThatThrownBy(() -> Alignment.build(scene, script, fixture(reordered), "a".repeat(64), speech(96_000), "b"))
                .isInstanceOf(MediaFailure.class);
    }

    @Test
    void longInternalSilenceBlocksTheNarration() {
        var scene = scene("scene-1", 1, List.of("s1"), "One. Two.", "", "");
        short[] pcm = speech(240_000);
        java.util.Arrays.fill(pcm, 20_000, 20_000 + 100_000, (short) 0);
        var timing = List.of(new FixtureCatalog.SegmentTiming("s1", List.of(new FixtureCatalog.Cue("One.", 0, 19_000),
                new FixtureCatalog.Cue("Two.", 125_000, 230_000))));
        assertThatThrownBy(() -> Alignment.build(scene, Map.of("s1", "One. Two."), fixture(timing), "a", pcm, "b"))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_NARRATION_SILENCE"));
    }

    @Test
    void subtitleTimesAreQuantisedFromFrames() {
        assertThat(SubtitleTrack.assTime(1)).isEqualTo("0:00:00.03");
        assertThat(SubtitleTrack.assTime(31)).isEqualTo("0:00:01.03");
        assertThat(SubtitleTrack.srtTime(1)).isEqualTo("00:00:00,033");
        assertThat(SubtitleTrack.srtTime(2)).isEqualTo("00:00:00,067");
        assertThat(SubtitleTrack.srtTime(1800)).isEqualTo("00:01:00,000");
    }

    @Test
    void assDataTextIsEscapedSoOverrideTagsCannotBeInjected() {
        var cue = new SubtitleTrack.Cue(1, "s", "s1", "{\\an8}moved \\N line", 0, 30, -1, List.of(),
                SubtitleTrack.Animation.FADE);
        String text = SubtitleTrack.assText(cue);
        assertThat(text).doesNotContain("{\\an8}").startsWith("\\{\\⁠an8\\}");
        assertThat(text).contains("\\⁠N");
    }

    @Test
    void cuesWrapIntoTwoBalancedLinesAndHighlightNumbersAndPhrases() {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 10);
        Font scaled = SubtitleTrack.scaled(font, 76);
        String firstHalf = "Now squeeze about 1.4 times";
        String secondHalf = "the mass of our Sun into a ball";
        String text = firstHalf + " " + secondHalf;
        // Logical SansSerif maps to different fonts on Windows and Linux.
        double lineLimit = Math.max(SubtitleTrack.width(scaled, firstHalf), SubtitleTrack.width(scaled, secondHalf));
        assertThat(SubtitleTrack.width(scaled, text)).isGreaterThan(lineLimit);
        int at = SubtitleTrack.wrap(text, scaled, lineLimit);
        assertThat(at).isPositive();
        assertThat(text.charAt(at)).isEqualTo(' ');
        assertThat(SubtitleTrack.width(scaled, text.substring(0, at))).isLessThanOrEqualTo(lineLimit);
        assertThat(SubtitleTrack.width(scaled, text.substring(at + 1))).isLessThanOrEqualTo(lineLimit);
        assertThat(SubtitleTrack.wrap("Short line", scaled, lineLimit)).isEqualTo(-1);
        var spans = SubtitleTrack.emphasis("About 117 Earth days, a billion tons.", List.of("billion tons"));
        assertThat(spans).extracting(SubtitleTrack.Span::start).containsExactly(6, 24);
        assertThatThrownBy(() -> SubtitleTrack.wrap("x".repeat(200), scaled, lineLimit))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("SUBTITLE_TOO_LONG"));
    }

    @Test
    void trackFollowsTheAlignmentAndRoundTripsThroughSrt() {
        var scene = scene("scene-1", 1, List.of("s1"), "A big claim. Then a twist.", "", "");
        var timing = List.of(new FixtureCatalog.SegmentTiming("s1", List.of(new FixtureCatalog.Cue("A big claim.", 1_000, 40_000),
                new FixtureCatalog.Cue("Then a twist.", 44_000, 90_000))));
        var alignment = Alignment.build(scene, Map.of("s1", "A big claim. Then a twist."), fixture(timing), "a",
                speech(96_000), "b");
        var timeline = Timeline.of(List.of("scene-1"), List.of(96_000L));
        var built = SubtitleTrack.build(List.of(alignment), timeline, new Font(Font.SANS_SERIF, Font.BOLD, 10),
                "Noto Sans KR", 76);
        var cues = built.track().cues();
        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).startFrame()).isEqualTo(1);
        assertThat(cues.get(0).endFrame()).isEqualTo(cues.get(1).startFrame()); // 3-frame gap held
        assertThat(cues.get(0).animation()).isEqualTo(SubtitleTrack.Animation.POP);
        var parsed = SubtitleTrack.parseSrt(built.track().srt());
        assertThat(parsed).extracting(SubtitleTrack.ParsedCue::text).containsExactly("A big claim.", "Then a twist.");
        assertThat(built.track().ass(false)).contains("Style: Main,Noto Sans KR,76,").contains("PlayResY: 1920");
        assertThat(built.track().ass(true)).contains(",&H00FFFFFF,&H00FFFFFF,-1,");
    }

    @Test
    void freeTextDirectionMapsOnlyToAllowListedPresets() {
        assertThat(RenderPlan.camera("Static close-up with a slow push-in; eye-level camera")).isEqualTo(RenderPlan.Camera.ZOOM_IN);
        assertThat(RenderPlan.camera("Wide shot; the camera pulls back as the shell expands")).isEqualTo(RenderPlan.Camera.ZOOM_OUT);
        assertThat(RenderPlan.camera("Animated compression shot; slight camera shake")).isEqualTo(RenderPlan.Camera.PUNCH_IN);
        assertThat(RenderPlan.camera("Locked-off frontal camera; the orbit marker finishes its lap")).isEqualTo(RenderPlan.Camera.STATIC);
        assertThat(RenderPlan.camera("Slow upward tilt with the sunrise")).isEqualTo(RenderPlan.Camera.PAN_UP);
        assertThat(RenderPlan.camera("Dynamic orbit camera around the impact")).isEqualTo(RenderPlan.Camera.PAN_RIGHT);
        assertThat(RenderPlan.camera("zoompan=z='9':d=1;[0:v]")).isNull();
        assertThat(RenderPlan.transition("Hard cut on the question")).isEqualTo(RenderPlan.Transition.CUT);
        assertThat(RenderPlan.transition("Smoke wipe")).isEqualTo(RenderPlan.Transition.FADE);
        assertThat(RenderPlan.transition("Spiral portal into the next scene")).isEqualTo(RenderPlan.Transition.CUT);
    }

    static FixtureCatalog catalog(boolean sounds) {
        List<FixtureCatalog.AudioFixture> audio = sounds ? List.of(
                new FixtureCatalog.AudioFixture("bgm", "MUSIC", "audio/m.wav", "0".repeat(64), 1, -20, RIGHTS),
                new FixtureCatalog.AudioFixture("hit", "SFX_PUNCH", "audio/h.wav", "0".repeat(64), 1, -10, RIGHTS),
                new FixtureCatalog.AudioFixture("whoosh", "SFX_TRANSITION", "audio/w.wav", "0".repeat(64), 1, -14, RIGHTS))
                : List.of();
        return new FixtureCatalog(1, "c", List.of(), List.of(), audio, List.of());
    }

    static Alignment aligned(String id, long samples, List<String> segments) {
        List<Alignment.Segment> list = new java.util.ArrayList<>();
        long step = samples / segments.size();
        for (int i = 0; i < segments.size(); i++) {
            list.add(new Alignment.Segment(segments.get(i), i * step, (i + 1) * step - 10,
                    List.of(new Alignment.Cue("w", i * step, (i + 1) * step - 10))));
        }
        return new Alignment(1, id, 1, "h", 48_000, samples, Alignment.VERIFIED, "CUE", "f", "s", "p", list, List.of(),
                List.of(), List.of());
    }

    @Test
    void planAddsFadeHandlesPunchFrameAndSoundPlacements() {
        var scenes = List.of(scene("a", 1, List.of("s1"), "x", "Static close-up with a slow push-in", "Flash to white"),
                scene("b", 2, List.of("s2", "s3"), "y", "slight camera shake", "Match cut"),
                scene("c", 3, List.of("s4"), "z", "unmapped direction", "Fade to end card"));
        var alignments = List.of(aligned("a", 480_000, List.of("s1")), aligned("b", 480_000, List.of("s2", "s3")),
                aligned("c", 480_000, List.of("s4")));
        var timeline = Timeline.of(List.of("a", "b", "c"), List.of(480_000L, 480_000L, 480_000L));
        var plan = RenderPlan.of(scenes, alignments, timeline, catalog(true));
        assertThat(plan.shots()).extracting(RenderPlan.Shot::camera).containsExactly(RenderPlan.Camera.ZOOM_IN,
                RenderPlan.Camera.PUNCH_IN, RenderPlan.Camera.ZOOM_IN);
        assertThat(plan.shots()).extracting(RenderPlan.Shot::transitionOut).containsExactly(RenderPlan.Transition.FADE,
                RenderPlan.Transition.CUT, RenderPlan.Transition.CUT);
        assertThat(plan.clipFrames(plan.shots().get(0))).isEqualTo(300 + RenderPlan.FADE_FRAMES);
        assertThat(plan.shots().get(1).punchFrame()).isEqualTo(150);
        assertThat(plan.diagnostics()).contains("DIRECTION_UNMAPPED:c");
        assertThat(plan.fadeOut()).isTrue();
        assertThat(plan.musicId()).isEqualTo("bgm");
        assertThat(plan.sounds()).extracting(RenderPlan.Sound::kind).containsExactlyInAnyOrder("SFX_TRANSITION", "SFX_PUNCH");

        String video = FilterGraphs.video(plan);
        assertThat(video).contains("xfade=transition=fade:duration=0.5:offset=10.0")
                .contains("concat=n=2:v=1:a=0,settb=1/30").contains("d=315:").contains("ass=filename=sub.ass:fontsdir=fonts")
                .contains("setparams=range=tv:color_primaries=bt709").doesNotContain("Flash").doesNotContain("shake");
        String audio = FilterGraphs.audio(plan, 3);
        assertThat(audio).contains("apad=whole_len=" + timeline.outputSamples()).contains("sidechaincompress")
                .contains("adelay=delays=" + (450L * 1600) + "S").contains("amix=inputs=4:normalize=0");
        assertThat(FilterGraphs.audio(RenderPlan.of(scenes, alignments, timeline, catalog(false)), 3))
                .contains("[narr]anull[aout]").doesNotContain("amix");
    }

    @Test
    void shortScenesAreAlwaysCutAndLoudnessValuesAreRangeChecked() {
        var scenes = List.of(scene("a", 1, List.of("s1"), "x", "push-in", "Dissolve"),
                scene("b", 2, List.of("s2"), "y", "push-in", "Cut"));
        var timeline = Timeline.of(List.of("a", "b"), List.of(20_000L, 480_000L));
        var plan = RenderPlan.of(scenes, List.of(aligned("a", 20_000, List.of("s1")), aligned("b", 480_000, List.of("s2"))),
                timeline, catalog(false));
        assertThat(plan.shots().getFirst().transitionOut()).isEqualTo(RenderPlan.Transition.CUT);
        assertThat(plan.diagnostics()).contains("SHORT_SCENE_CUT:a");
        assertThatThrownBy(() -> new FilterGraphs.Measured("-20.1", "-1.0", "5.0", "-30.0", "0:af=evil"))
                .isInstanceOfSatisfying(MediaFailure.class, f -> assertThat(f.code()).isEqualTo("MEDIA_LOUDNESS_INVALID"));
        assertThatThrownBy(() -> new FilterGraphs.Measured("-inf", "-1.0", "5.0", "-30.0", "0.1"))
                .isInstanceOf(MediaFailure.class);
        assertThat(FilterGraphs.loudnormSecondPass(new FilterGraphs.Measured("-20.10", "-3.00", "5.00", "-30.20", "0.10"),
                96_000)).startsWith("loudnorm=I=-16:TP=-1.5:LRA=11:measured_I=-20.10").contains("linear=true")
                .endsWith("atrim=end_sample=96000");
        assertThat(Instant.parse(MediaTimingAndGraphTest.RIGHTS.verifiedAt())).isNotNull();
    }
}
