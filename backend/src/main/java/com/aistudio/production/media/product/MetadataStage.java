package com.aistudio.production.media.product;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaAssets.Stored;
import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaProfile;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStageWork.Outcome;
import com.aistudio.production.contract.MediaStageWork.Result;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.media.storage.MediaWorkMapper;

/**
 * METADATA (D05): technical media metadata of the request built from finalised inputs and the deterministic render
 * plan: scene/frame mapping, subtitle and audio sources, simulated-effect operations and the rights of every
 * source asset. YouTube titles, descriptions and publishing settings belong to V2-06 and are not produced here.
 */
@Component
class MetadataStage {
    static final String VERSION = "metadata-1";

    private final StageSupport support;
    private final MediaWorkMapper items;

    MetadataStage(StageSupport support, MediaWorkMapper items) {
        this.support = support;
        this.items = items;
    }

    Result run(Context context) {
        var item = context.items().getFirst();
        if ("APPLIED".equals(item.state())) {
            return new Result(Outcome.APPLIED, null);
        }
        var input = support.input(context, item);
        var profile = support.profile(context);
        var catalog = support.catalog(profile);
        Map<String, Object> metadata = build(context, input, profile, catalog);
        byte[] bytes = support.encode(metadata);
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("schemaVersion", 1);
        lineage.put("stage", ProductionStage.METADATA.name());
        lineage.put("generator", VERSION);
        lineage.put("inputSha256", item.inputHash());
        lineage.put("planSha256", metadata.get("planSha256"));
        String verifiedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String rights = support.encodeText(FixtureCatalog.rightsRecord(FixtureCatalog.derived(verifiedAt), "",
                List.of()));
        try {
            support.assets().publish(context, List.of(new Payload(Role.MEDIA_METADATA, "application/json", bytes.length,
                    support.encodeText(lineage), rights, () -> new ByteArrayInputStream(bytes))));
        } catch (IOException ex) {
            throw MediaFailure.retry("MEDIA_PUBLISH_FAILED", "Metadata publication failed", ex);
        }
        return new Result(Outcome.APPLIED, null);
    }

    Map<String, Object> build(Context context, StageSupport.ItemInput input, MediaProfile profile,
            FixtureCatalog.Loaded catalog) {
        var inputs = SubtitleStage.inputs(support, context, input);
        int n = inputs.scenes().size();
        var images = support.inputs(context, ProductionStage.IMAGE, Role.NORMALIZED_IMAGE, n);
        var ass = support.inputs(context, ProductionStage.SUBTITLE, Role.ASS, 1).getFirst();
        var srt = support.inputs(context, ProductionStage.SUBTITLE, Role.SRT, 1).getFirst();
        var fonts = SubtitleStage.fonts(support, catalog);
        RenderPlan plan = RenderPlan.of(inputs.scenes(), inputs.alignments(), inputs.timeline(), catalog.catalog());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("generator", VERSION);
        m.put("mode", "FAKE");
        m.put("requestId", context.owner().requestId());
        m.put("channelId", context.channelId());
        m.put("contentId", context.contentId());
        m.put("runId", context.runId());
        m.put("revisionId", context.revisionId());
        m.put("channelConfigId", profile.channelConfigId());
        m.put("profileSha256", context.profileHash());
        m.put("profile", Map.of("language", profile.language(), "voiceId", profile.voiceId(),
                "visualPreset", profile.visualPreset(), "subtitlePreset", profile.subtitlePreset(),
                "outputPreset", profile.outputPreset(), "characterProfiles", profile.characterProfiles().size()));
        m.put("scriptSha256", input.scriptHash());
        m.put("scenesSha256", input.scenesHash());
        m.put("catalog", Map.of("id", catalog.catalog().catalogId(), "sha256", catalog.catalogSha256()));
        m.put("timeline", Map.of("fps", Timeline.FPS, "sampleRate", Alignment.RATE,
                "narrationSamples", inputs.timeline().narrationSamples(), "totalFrames", inputs.timeline().totalFrames(),
                "outputSamples", inputs.timeline().outputSamples()));
        m.put("plan", plan);
        m.put("planSha256", FixtureCatalog.sha256(support.encode(plan)));
        List<Map<String, Object>> scenes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var shot = plan.shots().get(i);
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("sceneId", inputs.scenes().get(i).id());
            scene.put("order", inputs.scenes().get(i).order());
            scene.put("segmentIds", inputs.scenes().get(i).segmentIds());
            scene.put("image", asset(images.get(i)));
            scene.put("narration", asset(inputs.pcm().get(i)));
            scene.put("alignment", asset(inputs.alignmentAssets().get(i)));
            scene.put("startFrame", shot.startFrame());
            scene.put("endFrame", shot.startFrame() + shot.frames());
            scene.put("camera", shot.camera().name());
            scene.put("transitionOut", shot.transitionOut().name());
            scene.put("timingSource", inputs.alignments().get(i).timingSource());
            scenes.add(scene);
        }
        m.put("scenes", scenes);
        m.put("subtitle", Map.of("ass", asset(ass), "srt", asset(srt), "fontSha256", fonts.sha256(),
                "fontFamily", support.settings().fontFamily()));
        m.put("rights", rights(inputs.scenes(), profile, catalog, plan, fonts));
        List<Map<String, Object>> effects = new ArrayList<>();
        for (ProductionStage stage : List.of(ProductionStage.IMAGE, ProductionStage.TTS)) {
            for (var effect : items.items(context.owner().requestId(), stage)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("stage", stage.name());
                e.put("itemId", effect.id());
                e.put("ordinal", effect.ordinal());
                e.put("operationId", effect.operationId());
                e.put("state", effect.state());
                effects.add(e);
            }
        }
        m.put("effects", effects);
        m.put("mockCost", Map.of("currency", "KRW", "unitPrice", support.settings().fakePriceKrw().toPlainString(),
                "effects", effects.size(), "note", "FAKE mock KRW; no external provider was called"));
        return m;
    }

    static Map<String, Object> asset(Stored stored) {
        return Map.of("assetId", stored.id(), "role", stored.role().name(), "sha256", stored.identity().sha256(),
                "bytes", stored.identity().bytes());
    }

    /** Rights of every source used: per-scene image and narration fixtures, music, sound effects and the font. */
    static List<Map<String, Object>> rights(List<ProductionPlanning.Scene> scenes, MediaProfile profile,
            FixtureCatalog.Loaded catalog, RenderPlan plan, SubtitleStage.Fonts fonts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var scene : scenes) {
            String prompt = FixtureCatalog.sha256(scene.imagePrompt().getBytes(StandardCharsets.UTF_8));
            var image = catalog.catalog().image(prompt).orElseThrow(() -> MediaFailure.blocked(
                    "MEDIA_RIGHTS_INCOMPLETE", "Image rights record is missing"));
            out.add(right("IMAGE", scene.id(), image.id(), image.sha256(), image.rights()));
            var narration = catalog.catalog().narration(profile.voiceId(), profile.language(), scene.narration())
                    .orElseThrow(() -> MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE", "Narration rights are missing"));
            out.add(right("NARRATION", scene.id(), narration.id(), narration.sha256(), narration.rights()));
        }
        List<String> used = new ArrayList<>();
        if (plan.musicId() != null) {
            used.add(plan.musicId());
        }
        plan.sounds().forEach(sound -> {
            if (!used.contains(sound.fixtureId())) {
                used.add(sound.fixtureId());
            }
        });
        for (String id : used) {
            var audio = catalog.catalog().audio().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(
                    () -> MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE", "Audio rights record is missing"));
            out.add(right(audio.kind(), null, audio.id(), audio.sha256(), audio.rights()));
        }
        out.add(right("FONT", null, fonts.fixture().id(), fonts.sha256(), fonts.fixture().rights()));
        for (var entry : out) {
            if (!((FixtureCatalog.Rights) entry.get("rights")).complete()) {
                throw MediaFailure.blocked("MEDIA_RIGHTS_INCOMPLETE", "Rights record is incomplete");
            }
        }
        return out;
    }

    private static Map<String, Object> right(String kind, String sceneId, String fixtureId, String sha256,
            FixtureCatalog.Rights rights) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        if (sceneId != null) {
            m.put("sceneId", sceneId);
        }
        m.put("fixtureId", fixtureId);
        m.put("sha256", sha256);
        m.put("rights", rights);
        return m;
    }
}
