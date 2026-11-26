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

    private Validation prompt(int index, String imagePrompt) {
        return review(with(list -> {
            Scene s = list.get(index);
            list.set(index, new Scene(s.id(), s.order(), s.segmentIds(), s.estimatedDurationMs(), s.narration(),
                    s.visualDescription(), imagePrompt, s.direction(), s.transition(), s.subtitleText()));
            return list;
        }));
    }

    private static List<String> newFacts(Validation validation) {
        return validation.findings().stream().filter(f -> f.code().equals("SCENE_NEW_FACT"))
                .flatMap(f -> f.references().stream()).toList();
    }

    @Test
    void numberlessFramingDoesNotIntroduceFacts() {
        for (String framing : List.of("Flat infographic of Venus, vertical, large labels",
                "Flat infographic of Venus, in a portrait composition", "Flat infographic of Venus, vertical layout",
                "Flat infographic of Venus, vertical frame", "Flat infographic of Venus, portrait framing")) {
            assertThat(prompt(0, framing).findings()).as(framing).isEmpty();
        }
    }

    @Test
    void ordinaryNinesAndSixteensAreStillComparedWithTheNarration() {
        assertThat(newFacts(prompt(0, "Venus has 16 moons, vertical frame"))).containsExactly("16");
        assertThat(newFacts(prompt(0, "Label reading 9 hours on the dial, vertical framing"))).containsExactly("9");
        assertThat(newFacts(prompt(0, "Sixteen craters shown, caption reading 16 craters"))).containsExactly("16");
        assertThat(newFacts(prompt(0, "Nine moons orbit Venus, vertical"))).containsExactly("9");
        // A colon separates numeric tokens under the same rule used by the script review.
        assertThat(newFacts(prompt(0, "Clock on the wall reading 9:16"))).containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "Vertical scale reading 19:16"))).containsExactly("19", "16");
    }

    @Test
    void numbersTheNarrationUsesStayAllowed() {
        // scene 1 narrates "about 243 Earth days ... only about 225"
        assertThat(prompt(0, "Dial labelled 243 days beside an orbit labelled 225 days, vertical composition").findings())
                .isEmpty();
    }

    @Test
    void numericFramingAndNearbyNumbersAreAllChecked() {
        assertThat(newFacts(prompt(0, "Vertical 9:16, infographic caption reading Venus has 16 moons")))
                .containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "Vertical 9:16, 9 moons around Venus"))).containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "9:16 composition, 16 moons and 243 days"))).containsExactly("9", "16");
        assertThat(blocks(prompt(0, "Vertical 9:16, 16 moons"))).containsExactly("SCENE_NEW_FACT");
        assertThat(newFacts(prompt(0, "Vertical 9:16 infographic, caption reading Venus has 16 moons")))
                .containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "9:16 composition with 16 moons and 243 days"))).containsExactly("9", "16");
    }

    @Test
    void numericFramingInstructionsStillRequireNarration() {
        for (String framing : List.of("Vertical 9:16", "Aspect ratio 9:16", "9:16 composition",
                "Portrait framing 9:16", "Vertical 9:16 frame", "9:16 vertical composition",
                "A cinematic view of Venus. Aspect ratio 9:16.", "Aspect ratio: 9:16", "Venus (vertical 9:16)",
                "Flat infographic of Venus, in a 9:16 aspect ratio", "Flat infographic of Venus, vertical format 9:16",
                "Venus; portrait 9 : 16; soft light", "Venus horizon\nvertical 9:16 composition")) {
            assertThat(newFacts(prompt(0, framing))).as(framing).containsExactly("9", "16");
        }
    }

    @Test
    void punctuationDoesNotExemptAVisualRatio() {
        for (String fact : List.of(
                // reproduced by the V2-03 independent re-review
                "The orbital resonance ratio is 9:16 vertical", "Orbital resonance ratio 9:16 composition",
                "The tower's mass ratio is 9:16 frame", "Mass ratio of 9:16 composition",
                // ratio or proportion words, verbs and content words in the token's clause
                "The tower's vertical 9:16 proportion", "Planets settle into a 9:16 vertical alignment",
                "Flat infographic of Venus in a 9:16 composition", "Flat infographic of Venus in a 9:16 aspect ratio",
                "The aspect ratio is 9:16", "Venus shown at 9:16", "Venus, 9:16", "The ratio: 9:16 vertical",
                "The mass ratio: vertical 9:16", "The mass ratio (9:16 vertical)",
                "The mass ratio \u2014 9:16 frame", "The mass ratio, 9:16 composition",
                "The mass ratio - 9:16 frame", "Vertical 9:16 and vertical 9:16")) {
            Validation validation = prompt(0, fact);
            assertThat(blocks(validation)).as(fact).containsExactly("SCENE_NEW_FACT");
            assertThat(newFacts(validation)).as(fact).containsExactly("9", "16");
        }
        assertThat(newFacts(prompt(0, "Vertical 9:16, 16 moons"))).containsExactly("9", "16");
        assertThat(blocks(prompt(0, "Vertical 9:16, 16 moons"))).containsExactly("SCENE_NEW_FACT");
    }

    @Test
    void aFactualRatioIsComparedWithTheNarrationEvenBesideRatioWords() {
        for (String fact : List.of("The orbital resonance ratio is 9:16", "The mass ratio is 9:16",
                "The gravitational ratio is 9:16", "The aspect ratio of the planet is 9:16",
                "Orbit diagram, resonance ratio 9:16", "Planet aspect ratio 9:16", "Frame showing a 9:16 ratio",
                "Orbit diagram in a 9:16 ratio of periods", "Composition: the mass ratio is 9:16")) {
            Validation validation = prompt(0, fact);
            assertThat(blocks(validation)).as(fact).containsExactly("SCENE_NEW_FACT");
            assertThat(newFacts(validation)).as(fact).containsExactly("9", "16");
        }
        assertThat(blocks(prompt(0, "Venus has 16 moons"))).containsExactly("SCENE_NEW_FACT");
        assertThat(newFacts(prompt(0, "Vertical 9:16, 16 moons"))).containsExactly("9", "16");
    }

    @Test
    void aFramingAdjectiveDoesNotExemptAnObjectOrMeasurementRatio() {
        for (String fact : List.of("vertical 9:16 ratio of the tower", "portrait 9:16 mass ratio",
                "vertical 9:16 aspect ratio of the tower", "9:16 aspect ratio of the tower")) {
            assertThat(newFacts(prompt(0, fact))).as(fact).containsExactly("9", "16");
            assertThat(blocks(prompt(0, fact))).as(fact).contains("SCENE_NEW_FACT");
        }
        assertThat(newFacts(prompt(0, "Vertical 9:16 aspect ratio, tower centered"))).containsExactly("9", "16");
    }

    @Test
    void aFrameTokenAndASeparateFactualRatioInOneSentence() {
        assertThat(newFacts(prompt(0, "Vertical 9:16 frame showing the orbital resonance ratio of 9:16")))
                .containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "9:16 composition, the mass ratio is 9:16"))).containsExactly("9", "16");
    }

    @Test
    void aRatioTheNarrationStatesStaysAllowed() {
        // scene 1 narrates "about 243 Earth days ... only about 225"
        assertThat(prompt(0, "Orbit diagram showing the 243:225 day ratio, vertical").findings()).isEmpty();
        assertThat(prompt(0, "The day to year ratio is 243:225, portrait composition").findings()).isEmpty();
    }

    @Test
    void sceneReviewUsesTheExistingNumberTokenizer() {
        assertThat(newFacts(prompt(0, "vertical 9:16, 16 moons"))).containsExactly("9", "16");
        assertThat(newFacts(prompt(0, "aspect 9:16.5"))).containsExactly("9", "16.5");
        assertThat(newFacts(prompt(0, "vertical 9:160"))).containsExactly("9", "160");
        assertThat(newFacts(prompt(0, "vertical 9:16:2"))).containsExactly("9", "16", "2");
        assertThat(newFacts(prompt(0, null))).isEmpty();
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
