package com.aistudio.production.stage;

import static com.aistudio.production.stage.PlanningTestSupport.REVIEW;
import static com.aistudio.production.stage.PlanningTestSupport.VENUS;
import static com.aistudio.production.stage.PlanningTestSupport.blocks;
import static com.aistudio.production.stage.PlanningTestSupport.recommendations;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;

/** SCENES review: segment coverage, order, timing, server-derived text and visual safety (R05). */
class ScenesReviewTest {
    private final PlanningTestSupport.Run venus = PlanningTestSupport.run(VENUS, List.of());
    private final Scenes base = venus.scenes();

    private Validation review(Scenes scenes) {
        return REVIEW.review(ProductionStage.SCENES, venus.scenesInput(), scenes);
    }

    private Scenes with(UnaryOperator<List<Scene>> change) {
        return new Scenes(base.visualStyle(), change.apply(new ArrayList<>(base.scenes())));
    }

    private static Scene segments(Scene s, List<String> ids, String narration) {
        return new Scene(s.id(), s.order(), ids, s.estimatedDurationMs(), narration, s.visualDescription(),
                s.imagePrompt(), s.direction(), s.transition(), narration);
    }

    private String text(String... ids) {
        return String.join(" ", List.of(ids).stream().map(id -> venus.script().segments().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow().narration()).toList());
    }

    @Test
    void fixtureScenesPass() {
        assertThat(review(base).findings()).isEmpty();
        assertThat(base.scenes()).extracting(Scene::order).containsExactly(1, 2, 3);
    }

    @Test
    void missingSegmentIsBlocked() {
        var scenes = with(list -> {
            list.set(2, segments(list.get(2), List.of("s5"), text("s5")));
            return list;
        });
        assertThat(blocks(review(scenes))).containsExactly("SCENE_SEGMENT_MISSING");
    }

    @Test
    void duplicatedSegmentIsBlocked() {
        var scenes = with(list -> {
            list.set(1, segments(list.get(1), List.of("s2", "s3", "s4"), text("s2", "s3", "s4")));
            return list;
        });
        assertThat(blocks(review(scenes))).containsExactly("SCENE_SEGMENT_DUPLICATE");
    }

    @Test
    void segmentsOutOfScriptOrderAreBlocked() {
        var scenes = with(list -> {
            list.set(0, segments(list.get(0), List.of("s3", "s4"), text("s3", "s4")));
            list.set(1, segments(list.get(1), List.of("s1", "s2"), text("s1", "s2")));
            return list;
        });
        assertThat(blocks(review(scenes))).containsExactly("SCENE_SEGMENT_ORDER");
    }

    @Test
    void unknownSegmentIsBlocked() {
        var scenes = with(list -> {
            list.set(2, segments(list.get(2), List.of("s5", "s6", "s9"), text("s5", "s6")));
            return list;
        });
        assertThat(blocks(review(scenes))).contains("SCENE_SEGMENT_UNKNOWN");
    }

    @Test
    void sceneOrderNumbersMustRunOneToN() {
        var scenes = with(list -> {
            Scene s = list.get(1);
            list.set(1, new Scene(s.id(), 5, s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    s.visualDescription(), s.imagePrompt(), s.direction(), s.transition(), s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(scenes))).containsExactly("SCENE_ORDER");
    }

    @Test
    void sceneLengthsMustAddUpToTheScriptEstimate() {
        var scenes = with(list -> {
            Scene s = list.get(0);
            list.set(0, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs() + 2_000, s.narration(),
                    s.visualDescription(), s.imagePrompt(), s.direction(), s.transition(), s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(scenes))).containsExactly("SCENE_DURATION_TOTAL");
        var zero = with(list -> {
            Scene s = list.get(0);
            list.set(0, new Scene(s.id(), s.order(), s.segmentIds(), 0, s.narration(), s.visualDescription(),
                    s.imagePrompt(), s.direction(), s.transition(), s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(zero))).contains("SCENE_DURATION_INVALID", "SCENE_DURATION_TOTAL");
    }

    @Test
    void narrationAndSubtitleMustBeTheFixedScriptText() {
        var subtitle = with(list -> {
            Scene s = list.get(0);
            list.set(0, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    s.visualDescription(), s.imagePrompt(), s.direction(), s.transition(), "A day longer than a year!"));
            return list;
        });
        assertThat(blocks(review(subtitle))).containsExactly("SCENE_TEXT_MISMATCH");
        var narration = with(list -> {
            Scene s = list.get(1);
            list.set(1, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), "Something else.",
                    s.visualDescription(), s.imagePrompt(), s.direction(), s.transition(), s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(narration))).containsExactly("SCENE_TEXT_MISMATCH");
    }

    @Test
    void visualsMayNotAddFactsOrPoseAsRealEvidence() {
        var newNumber = with(list -> {
            Scene s = list.get(1);
            list.set(1, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    s.visualDescription(), s.imagePrompt() + ", caption reading 470 degrees", s.direction(),
                    s.transition(), s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(newNumber))).containsExactly("SCENE_NEW_FACT");
        var footage = with(list -> {
            Scene s = list.get(2);
            list.set(2, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    "Real photo of the impact on young Venus", s.imagePrompt(), s.direction(), s.transition(),
                    s.subtitleText()));
            return list;
        });
        assertThat(blocks(review(footage))).containsExactly("SCENE_PRESENTED_AS_EVIDENCE");
        var empty = with(list -> {
            Scene s = list.get(0);
            list.set(0, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    " ", null, s.direction(), null, s.subtitleText()));
            return list;
        });
        Validation validation = review(empty);
        assertThat(blocks(validation)).containsExactly("SCENE_VISUAL_MISSING");
        assertThat(recommendations(validation)).containsExactly("SCENE_TRANSITION_MISSING");
    }

    @Test
    void missingChannelStyleOrVerticalFramingIsARecommendation() {
        Validation validation = review(new Scenes("Bold flat infographic", base.scenes()));
        assertThat(validation.blocked()).isFalse();
        assertThat(recommendations(validation)).containsExactly("CHANNEL_STYLE_NOT_APPLIED", "FRAMING_NOT_VERTICAL");
    }

    @Test
    void emptyOrOversizedPlansAreBlocked() {
        assertThat(blocks(review(new Scenes(base.visualStyle(), List.of())))).containsExactly("SCENE_STRUCTURE");
        assertThat(review(null).blocked()).isTrue();
    }
}
