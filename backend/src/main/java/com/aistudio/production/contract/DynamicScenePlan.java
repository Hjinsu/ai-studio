package com.aistudio.production.contract;

import java.util.List;
import java.util.Objects;

/** Editing intents. The renderer resolves these against the measured narration and clip timeline. */
public record DynamicScenePlan(int schemaVersion, List<Scene> scenes) {
    public DynamicScenePlan {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported scene plan version");
        }
        scenes = List.copyOf(scenes);
        if (scenes.isEmpty() || scenes.stream().map(Scene::id).distinct().count() != scenes.size()) {
            throw new IllegalArgumentException("Scenes must have unique IDs");
        }
    }

    public enum Media { IMAGE, IMAGE_TO_VIDEO, TEXT_TO_VIDEO, PROCEDURAL_ANIMATION }
    public enum Priority { OPTIONAL, NORMAL, CRITICAL }
    public enum Category { BGM, FOLEY, SFX, AMBIENCE, COMEDY_SFX, TRANSITION_SFX, SILENCE }
    public enum AnchorType { SCENE_START, SCENE_END, SEGMENT, TRANSITION, SCREEN_EVENT }
    public enum Portrayal { REALISTIC, STYLISTIC }

    public record Scene(String id, List<String> segmentIds, int estimatedDurationMs, Media media,
            List<Media> alternatives, boolean subjectMotionRequired, Priority priority, List<SoundEvent> soundEvents) {
        public Scene {
            requireText(id);
            segmentIds = List.copyOf(segmentIds);
            segmentIds.forEach(DynamicScenePlan::requireText);
            alternatives = List.copyOf(alternatives);
            soundEvents = List.copyOf(soundEvents);
            Objects.requireNonNull(media);
            Objects.requireNonNull(priority);
            if (estimatedDurationMs < 1 || estimatedDurationMs > 60_000 || segmentIds.isEmpty()
                    || soundEvents.stream().map(SoundEvent::id).distinct().count() != soundEvents.size()) {
                throw new IllegalArgumentException("Invalid scene timing or sound event identity");
            }
        }
    }

    public record Anchor(AnchorType type, String reference) {
        public Anchor {
            Objects.requireNonNull(type);
            if (type == AnchorType.SEGMENT || type == AnchorType.SCREEN_EVENT) {
                requireText(reference);
            }
        }
    }

    public record SoundEvent(String id, Category category, String subjectAction, String mood, List<String> searchTags,
            Anchor anchor, int estimatedOffsetMs, Integer durationMs, Integer suggestedGainDb, Priority priority,
            Portrayal portrayal) {
        public SoundEvent {
            requireText(id);
            requireText(subjectAction);
            searchTags = List.copyOf(searchTags);
            searchTags.forEach(DynamicScenePlan::requireText);
            Objects.requireNonNull(category);
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(priority);
            Objects.requireNonNull(portrayal);
            if (estimatedOffsetMs < 0 || estimatedOffsetMs > 60_000
                    || (durationMs != null && (durationMs < 1 || durationMs > 60_000))
                    || (category == Category.SILENCE && durationMs == null)
                    || (suggestedGainDb != null && (suggestedGainDb < -60 || suggestedGainDb > 0))) {
                throw new IllegalArgumentException("Invalid sound event timing or gain");
            }
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Expected nonblank editorial text");
        }
    }
}
