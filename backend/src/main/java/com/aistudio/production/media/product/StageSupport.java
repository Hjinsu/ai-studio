package com.aistudio.production.media.product;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaAssets;
import com.aistudio.production.contract.MediaAssets.Stored;
import com.aistudio.production.contract.MediaProcessRunner;
import com.aistudio.production.contract.MediaProfile;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStageWork.Item;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.planning.PlanningResultMapper;

import tools.jackson.databind.json.JsonMapper;

/** Shared, read-only access for the product stages: frozen inputs, approved planning bodies, verified assets. */
@Component
class StageSupport {
    /** Mirrors the frozen media item input written by the shared executor (schema version 1). */
    record ItemInput(int version, long requestId, long runId, long revisionId, String profileHash, String scenesHash,
            String scriptHash, ProductionStage stage, ProductionPlanning.Scene scene) {}

    private final JsonMapper json;
    private final PlanningResultMapper planning;
    private final ProductSettings settings;
    private final MediaAssets assets;
    private final ObjectProvider<MediaProcessRunner> runner;

    StageSupport(JsonMapper json, PlanningResultMapper planning, ProductSettings settings, MediaAssets assets,
            ObjectProvider<MediaProcessRunner> runner) {
        this.json = json;
        this.planning = planning;
        this.settings = settings;
        this.assets = assets;
        this.runner = runner;
    }

    JsonMapper json() {
        return json;
    }

    ProductSettings settings() {
        return settings;
    }

    MediaAssets assets() {
        return assets;
    }

    MediaProcessRunner runner() {
        return runner.getIfAvailable();
    }

    MediaProfile profile(Context context) {
        if (!PlanningJson.matches(context.profileJson(), context.profileHash())) {
            throw MediaFailure.blocked("MEDIA_PROFILE_INTEGRITY", "Frozen media profile hash mismatch");
        }
        MediaProfile profile = json.readValue(context.profileJson(), MediaProfile.class);
        if (profile.version() != 1 || !"SHORTS_ASS_1".equals(profile.subtitlePreset())
                || !"SHORTS_1080P30_1".equals(profile.outputPreset())) {
            throw MediaFailure.blocked("MEDIA_PROFILE_UNSUPPORTED", "Unsupported media profile preset");
        }
        return profile;
    }

    FixtureCatalog.Loaded catalog(MediaProfile profile) {
        return FixtureCatalog.load(settings.fixtureRoot(), profile.fixtureCatalog(), json);
    }

    ItemInput input(Context context, Item item) {
        if (!PlanningJson.matches(item.inputJson(), item.inputHash())) {
            throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Frozen media input hash mismatch");
        }
        ItemInput input = json.readValue(item.inputJson(), ItemInput.class);
        if (input.version() != 1 || input.requestId() != context.owner().requestId() || input.stage() != context.stage()
                || input.runId() != context.runId() || input.revisionId() != context.revisionId()
                || !context.profileHash().equals(input.profileHash())) {
            throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Frozen media input belongs to another request");
        }
        return input;
    }

    /** The approved SCENES list, verified against the hash frozen in the item input. */
    List<ProductionPlanning.Scene> scenes(Context context, ItemInput input) {
        var applied = planning.accepted(context.owner().requestId(), ProductionStage.SCENES);
        if (applied == null || !applied.bodyHash().equals(input.scenesHash())
                || !PlanningJson.matches(applied.bodyJson(), applied.bodyHash())
                || json.readValue(applied.validationJson(), ProductionPlanning.Validation.class).blocked()) {
            throw MediaFailure.blocked("MEDIA_PLANNING_MISMATCH", "Approved scenes are unavailable or changed");
        }
        var scenes = json.readValue(applied.bodyJson(), ProductionPlanning.Scenes.class).scenes();
        if (scenes.isEmpty() || scenes.size() > 20) {
            throw MediaFailure.blocked("MEDIA_PLANNING_MISMATCH", "Scene count is outside 1..20");
        }
        return scenes;
    }

    /**
     * Segment narration by ID from the approved SCRIPT, after checking that the scenes use every script segment
     * exactly once in script order and that each scene narration is its segments joined by single spaces.
     */
    Map<String, String> script(Context context, ItemInput input, List<ProductionPlanning.Scene> scenes) {
        var applied = planning.accepted(context.owner().requestId(), ProductionStage.SCRIPT);
        if (applied == null || !applied.bodyHash().equals(input.scriptHash())
                || !PlanningJson.matches(applied.bodyJson(), applied.bodyHash())
                || json.readValue(applied.validationJson(), ProductionPlanning.Validation.class).blocked()) {
            throw MediaFailure.blocked("MEDIA_PLANNING_MISMATCH", "Approved script is unavailable or changed");
        }
        var script = json.readValue(applied.bodyJson(), ProductionPlanning.Script.class);
        Map<String, String> narration = new LinkedHashMap<>();
        for (var segment : script.segments()) {
            if (narration.put(segment.id(), segment.narration()) != null) {
                throw MediaFailure.blocked("MEDIA_SCRIPT_MISMATCH", "Duplicate script segment");
            }
        }
        List<String> used = scenes.stream().flatMap(scene -> scene.segmentIds().stream()).toList();
        Set<String> unique = new HashSet<>(used);
        if (unique.size() != used.size() || !used.equals(List.copyOf(narration.keySet()))) {
            throw MediaFailure.blocked("MEDIA_SCRIPT_MISMATCH", "Scenes omit, repeat or reorder script segments");
        }
        for (var scene : scenes) {
            String joined = String.join(" ", scene.segmentIds().stream().map(narration::get).toList());
            if (!joined.equals(scene.narration())) {
                throw MediaFailure.blocked("MEDIA_SCRIPT_MISMATCH", "Scene narration differs from the script");
            }
        }
        return narration;
    }

    List<Stored> inputs(Context context, ProductionStage stage, Role role, int expected) {
        List<Stored> found = assets.inputs(context, stage, role);
        if (found.size() != expected) {
            throw MediaFailure.blocked("MEDIA_INPUT_INCOMPLETE", stage + " " + role + " inputs are incomplete");
        }
        return found;
    }

    FileChannel open(Context context, Stored stored) {
        try {
            return assets.open(context, stored);
        } catch (IOException ex) {
            throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Stored media failed verification");
        }
    }

    byte[] read(Context context, Stored stored) {
        if (stored.identity().bytes() > FixtureCatalog.MAX_FILE) {
            throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Stored media is too large to read in memory");
        }
        try (FileChannel channel = open(context, stored)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) stored.identity().bytes());
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Stored media is shorter than recorded");
                }
            }
            return buffer.array();
        } catch (IOException ex) {
            throw MediaFailure.blocked("MEDIA_INPUT_INTEGRITY", "Stored media cannot be read");
        }
    }

    Alignment alignment(Context context, Stored stored) {
        return json.readValue(new String(read(context, stored), StandardCharsets.UTF_8), Alignment.class);
    }

    /**
     * Deterministic JSON for product media documents (keys sorted, records in alphabetical order). Unlike the
     * planning canonical form it allows decimal measurements such as LUFS and scale factors.
     */
    static final JsonMapper SORTED = JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    byte[] encode(Object value) {
        return encodeText(value).getBytes(StandardCharsets.UTF_8);
    }

    String encodeText(Object value) {
        return SORTED.writeValueAsString(value);
    }
}
