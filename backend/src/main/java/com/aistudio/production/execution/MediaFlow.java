package com.aistudio.production.execution;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaEffectPort;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.media.MediaAvailability;
import com.aistudio.production.media.storage.MediaProfileMapper;
import com.aistudio.production.media.storage.LocalMediaAssets;
import com.aistudio.production.media.storage.MediaWorkMapper;
import com.aistudio.production.planning.PlanningResultMapper;

import tools.jackson.databind.json.JsonMapper;

@Component
class MediaFlow {
    record Input(int version, long requestId, long runId, long revisionId, String profileHash, String scenesHash,
            String scriptHash, ProductionStage stage, ProductionPlanning.Scene scene) {}

    record Frozen(PlanningJson.Encoded profile, long revisionId, List<MediaStageWork.Item> items) {}

    private final MediaAvailability availability;
    private final ObjectProvider<MediaStageWork> work;
    private final MediaProfileMapper profiles;
    private final MediaWorkMapper items;
    private final PlanningResultMapper planning;
    private final JsonMapper json;
    private final LocalMediaAssets assets;
    private final MediaEffectPort effects;

    MediaFlow(MediaAvailability availability, ObjectProvider<MediaStageWork> work, MediaProfileMapper profiles,
            MediaWorkMapper items, PlanningResultMapper planning, JsonMapper json, LocalMediaAssets assets,
            MediaEffectPort effects) {
        this.availability = availability;
        this.work = work;
        this.profiles = profiles;
        this.items = items;
        this.planning = planning;
        this.json = json;
        this.assets = assets;
        this.effects = effects;
    }

    boolean ready() {
        return availability.ready();
    }

    void finishStage(long requestId) {
        availability.finishStage(requestId);
    }

    MediaStageWork.Result recoverCosts(MediaStageWork.Context context) {
        if (context.stage() != ProductionStage.IMAGE && context.stage() != ProductionStage.TTS) {
            return new MediaStageWork.Result(context.cancelled().getAsBoolean()
                    ? MediaStageWork.Outcome.BLOCKED : MediaStageWork.Outcome.RETRY, "MEDIA_UNAVAILABLE");
        }
        boolean pending = false;
        for (var frozen : context.items()) {
            if ("APPLIED".equals(frozen.state())) {
                continue;
            }
            if (frozen.operationId() == null) {
                pending = true;
                continue;
            }
            var item = new MediaEffectPort.Item(context.owner(), frozen.id());
            var result = effects.recover(item);
            if (result.disposition() != ProductionCostPort.Disposition.RECEIPT) {
                return new MediaStageWork.Result(MediaStageWork.Outcome.RECOVERY_REQUIRED, "MEDIA_EFFECT_PENDING");
            }
            effects.apply(item, result.receipt().operationId());
            if (result.receipt().outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
                return new MediaStageWork.Result(MediaStageWork.Outcome.BLOCKED, "MEDIA_EFFECT_FAILED");
            }
        }
        return pending
                ? new MediaStageWork.Result(MediaStageWork.Outcome.RETRY, "MEDIA_UNAVAILABLE")
                : new MediaStageWork.Result(MediaStageWork.Outcome.APPLIED, null);
    }

    MediaStageWork work() {
        return work.getObject();
    }

    static boolean paidStage(ProductionStage stage) {
        return stage == ProductionStage.IMAGE || stage == ProductionStage.TTS;
    }

    static boolean stage(ProductionStage stage) {
        return switch (stage) {
            case IMAGE, TTS, SUBTITLE, RENDER, METADATA, QC -> true;
            default -> false;
        };
    }

    Frozen freeze(StepOwner owner, ProductionStage stage, boolean recoveryOnly) {
        var profile = profiles.find(owner.requestId());
        var snapshot = planning.snapshot(owner.requestId());
        if (snapshot == null || profile == null || !PlanningJson.matches(profile.json(), profile.hash())) {
            throw new IllegalStateException("Media profile is unavailable");
        }
        var fixed = items.items(owner.requestId(), stage);
        if (fixed.isEmpty() && !recoveryOnly) {
            var scenes = accepted(owner.requestId(), ProductionStage.SCENES);
            var script = accepted(owner.requestId(), ProductionStage.SCRIPT);
            if (stage == ProductionStage.IMAGE) {
                List<ProductionPlanning.Scene> ordered = json.readValue(scenes.bodyJson(), ProductionPlanning.Scenes.class)
                        .scenes();
                if (!items.items(owner.requestId(), ProductionStage.TTS).isEmpty() || ordered.isEmpty()
                        || ordered.size() > 20) {
                    throw new IllegalStateException("Unexpected media scene set");
                }
                for (ProductionStage kind : List.of(ProductionStage.IMAGE, ProductionStage.TTS)) {
                    for (int i = 0; i < ordered.size(); i++) {
                        var scene = ordered.get(i);
                        var input = new Input(1, owner.requestId(), snapshot.runId(), snapshot.revisionId(),
                                profile.hash(), scenes.bodyHash(), script.bodyHash(), kind, scene);
                        insert(input, "scene-" + (i + 1), i + 1);
                    }
                }
                for (ProductionStage kind : List.of(ProductionStage.SUBTITLE, ProductionStage.RENDER,
                        ProductionStage.METADATA, ProductionStage.QC)) {
                    insert(new Input(1, owner.requestId(), snapshot.runId(), snapshot.revisionId(), profile.hash(),
                            scenes.bodyHash(), script.bodyHash(), kind, null), "video", 1);
                }
            } else {
                throw new IllegalStateException("Expected media items were not frozen");
            }
            fixed = items.items(owner.requestId(), stage);
        }
        var verified = new ArrayList<MediaStageWork.Item>(fixed.size());
        for (var item : fixed) {
            if (!PlanningJson.matches(item.inputJson(), item.inputSha256())) {
                throw new IllegalStateException("Media input integrity check failed");
            }
            verified.add(new MediaStageWork.Item(item.id(), item.ordinal(), item.inputJson(), item.inputSha256(),
                    item.state(), item.operationId()));
        }
        return new Frozen(profile, snapshot.revisionId(), List.copyOf(verified));
    }

    void verifyQc(MediaStageWork.Context context) throws java.io.IOException {
        assets.verifyQc(context);
    }

    /** Every frozen item of the local stage already holds its committed result (B02 recovery, read-only). */
    boolean persisted(long requestId, ProductionStage stage) {
        var fixed = items.items(requestId, stage);
        return !fixed.isEmpty() && fixed.stream().allMatch(item -> "APPLIED".equals(item.state()));
    }

    boolean applied(long requestId, ProductionStage stage) {
        var fixed = items.items(requestId, stage);
        if (fixed.isEmpty() || fixed.stream().anyMatch(item -> !"APPLIED".equals(item.state()))) {
            return false;
        }
        if (stage == ProductionStage.QC) {
            var qc = fixed.getFirst();
            assets.finalizeQc(requestId, qc.id(), qc.attemptNo());
        }
        return true;
    }

    private PlanningResultMapper.Applied accepted(long requestId, ProductionStage stage) {
        var result = planning.accepted(requestId, stage);
        if (result == null || !PlanningJson.matches(result.bodyJson(), result.bodyHash())
                || json.readValue(result.validationJson(), ProductionPlanning.Validation.class).blocked()) {
            throw new IllegalStateException("Approved planning input is unavailable");
        }
        return result;
    }

    private void insert(Input input, String target, int ordinal) {
        if (items.insert(input.requestId(), input.stage(), target, ordinal, PlanningJson.encode(input)) != 1) {
            throw new IllegalStateException("Cannot freeze media step input");
        }
    }
}
