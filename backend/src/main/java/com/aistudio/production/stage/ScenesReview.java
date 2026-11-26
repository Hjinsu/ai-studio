package com.aistudio.production.stage;

import static com.aistudio.production.stage.Findings.blank;
import static com.aistudio.production.stage.Findings.list;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Validation;

/**
 * SCENES review (R05). Every script segment appears exactly once, in script order; scene order is 1..n; each scene
 * has a positive length and the lengths add up to the script estimate; narration and subtitle text are exactly the
 * server-derived text of the scene's segments; the visual fields are present and add no new facts (numbers the
 * narration does not use, or a generated image presented as real footage).
 */
public final class ScenesReview {
    static final int MAX_SCENES = 20;
    static final int TOTAL_TOLERANCE_MS = 500;
    static final int SHORT_SCENE_MS = 1_500;
    private static final Pattern EVIDENCE_LOOK = Pattern.compile(
            "\\b(real|actual|authentic|genuine|unedited)\\s+(photo|photograph|footage|image|recording|video)s?\\b"
                    + "|\\b(nasa|esa|telescope|satellite|news)\\s+(photo|photograph|footage|image)s?\\b");

    public Validation review(ScenesInput input, Scenes scenes) {
        Findings findings = new Findings();
        List<Segment> segments = list(input.script().segments());
        List<Scene> items = scenes == null ? List.of() : list(scenes.scenes());
        if (scenes == null || items.isEmpty() || items.size() > MAX_SCENES
                || items.size() != scenes.scenes().size()) {
            findings.block("SCENE_STRUCTURE", "scenes", List.of(), "Return 1 to " + MAX_SCENES + " scenes.");
            return findings.validation();
        }
        Map<String, String> narration = segments.stream()
                .collect(Collectors.toMap(Segment::id, Segment::narration, (a, b) -> a));
        Set<String> ids = new HashSet<>();
        List<String> covered = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < items.size(); i++) {
            Scene scene = items.get(i);
            String location = "scenes[" + scene.id() + "]";
            if (blank(scene.id()) || !ids.add(scene.id())) {
                findings.block("SCENE_STRUCTURE", location, List.of(), "Give every scene a unique id.");
            }
            if (scene.order() != i + 1) {
                findings.block("SCENE_ORDER", location, List.of(Integer.toString(scene.order())),
                        "Number scenes 1.." + items.size() + " in playback order.");
            }
            List<String> sceneSegments = list(scene.segmentIds());
            if (sceneSegments.isEmpty()) {
                findings.block("SCENE_SEGMENT_MISSING", location, List.of(), "Each scene narrates at least one segment.");
            }
            covered.addAll(sceneSegments);
            if (scene.estimatedDurationMs() <= 0) {
                findings.block("SCENE_DURATION_INVALID", location, List.of(), "Use a positive length in milliseconds.");
            } else if (scene.estimatedDurationMs() < SHORT_SCENE_MS) {
                findings.recommend("SCENE_TOO_SHORT", location, List.of(), "Very short scenes are hard to read.");
            }
            total += Math.max(0, scene.estimatedDurationMs());
            String expected = sceneSegments.stream().map(id -> narration.getOrDefault(id, ""))
                    .collect(Collectors.joining(" "));
            if (!expected.equals(scene.narration()) || !expected.equals(scene.subtitleText())) {
                findings.block("SCENE_TEXT_MISMATCH", location, sceneSegments,
                        "Narration and subtitle text must be the fixed script text of the scene's segments.");
            }
            visual(scene, location, findings);
        }
        coverage(segments, covered, findings);
        int expectedTotal = input.script().estimatedDurationMs();
        if (Math.abs(total - expectedTotal) > TOTAL_TOLERANCE_MS) {
            findings.block("SCENE_DURATION_TOTAL", "scenes", List.of(Long.toString(total),
                    Integer.toString(expectedTotal)), "Scene lengths must add up to the script estimate.");
        }
        style(input, scenes, findings);
        return findings.validation();
    }

    private static void coverage(List<Segment> segments, List<String> covered, Findings findings) {
        List<String> expected = segments.stream().map(Segment::id).toList();
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (String id : covered) {
            if (!seen.add(id)) {
                duplicated.add(id);
            }
        }
        Set<String> unknown = new LinkedHashSet<>(covered);
        unknown.removeAll(Set.copyOf(expected));
        List<String> missing = expected.stream().filter(id -> !seen.contains(id)).toList();
        if (!missing.isEmpty()) {
            findings.block("SCENE_SEGMENT_MISSING", "scenes", missing, "Every script segment must appear in a scene.");
        }
        if (!duplicated.isEmpty()) {
            findings.block("SCENE_SEGMENT_DUPLICATE", "scenes", List.copyOf(duplicated),
                    "A segment may appear in only one scene.");
        }
        if (!unknown.isEmpty()) {
            findings.block("SCENE_SEGMENT_UNKNOWN", "scenes", List.copyOf(unknown),
                    "Reference only segments of the fixed script.");
        }
        if (missing.isEmpty() && duplicated.isEmpty() && unknown.isEmpty() && !covered.equals(expected)) {
            findings.block("SCENE_SEGMENT_ORDER", "scenes", covered, "Scenes must follow the script order.");
        }
    }

    private static void visual(Scene scene, String location, Findings findings) {
        if (blank(scene.visualDescription()) || blank(scene.imagePrompt()) || blank(scene.direction())) {
            findings.block("SCENE_VISUAL_MISSING", location, List.of(),
                    "Describe the subject, background, composition, camera and lighting, and give an image prompt.");
        }
        if (blank(scene.transition())) {
            findings.recommend("SCENE_TRANSITION_MISSING", location, List.of(), "Name the transition into the next scene.");
        }
        Set<String> narrated = Statements.numbers(scene.narration());
        Function<String, Set<String>> added = text -> {
            Set<String> numbers = new LinkedHashSet<>(Statements.numbers(text));
            numbers.remove("9");
            numbers.remove("16"); // the 9:16 frame is a format, not a claim
            numbers.removeAll(narrated);
            return numbers;
        };
        Set<String> extra = new LinkedHashSet<>();
        extra.addAll(added.apply(scene.visualDescription()));
        extra.addAll(added.apply(scene.imagePrompt()));
        extra.addAll(added.apply(scene.direction()));
        if (!extra.isEmpty()) {
            findings.block("SCENE_NEW_FACT", location, List.copyOf(extra),
                    "Visuals must not add numbers or facts the narration does not state.");
        }
        String visuals = (scene.visualDescription() + " " + scene.imagePrompt() + " " + scene.direction())
                .toLowerCase(Locale.ROOT);
        if (EVIDENCE_LOOK.matcher(visuals).find()) {
            findings.block("SCENE_PRESENTED_AS_EVIDENCE", location, List.of(),
                    "Generated images must not be presented as real photographs or footage.");
        }
    }

    private static void style(ScenesInput input, Scenes scenes, Findings findings) {
        String channel = input.snapshot().visualStyle();
        String style = scenes.visualStyle() == null ? "" : scenes.visualStyle();
        if (blank(style)) {
            findings.block("SCENE_VISUAL_MISSING", "scenes.visualStyle", List.of(), "State the common visual style.");
        } else if (!blank(channel) && !style.toLowerCase(Locale.ROOT).contains(channel.trim().toLowerCase(Locale.ROOT))) {
            findings.recommend("CHANNEL_STYLE_NOT_APPLIED", "scenes.visualStyle", List.of(),
                    "Carry the channel visual style into the common style.");
        }
        if (!style.contains("9:16") && !style.toLowerCase(Locale.ROOT).contains("vertical")) {
            findings.recommend("FRAMING_NOT_VERTICAL", "scenes.visualStyle", List.of(),
                    "Plan for a vertical 9:16 frame with the subject inside the safe area.");
        }
    }
}
