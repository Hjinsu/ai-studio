package com.aistudio.production.stage;

import com.aistudio.production.contract.ProductionPlanning.InputVersion;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;

/**
 * Local review of an adopted planning result against its fixed input (V2-03). Deterministic and free of external
 * calls, so the executor can compute it before the fenced transaction that stores it with the receipt and the step
 * state. Findings are either required blocks or recommendations; there is no review wait state and no automatic
 * rewrite.
 */
public final class PlanningReview {
    /** Provider versions of FAKE runs; only these may use offline FIXTURE documents as evidence. */
    static final String FAKE_PROVIDER_PREFIX = "fake-";

    private final ResearchReview research;
    private final ScriptReview script = new ScriptReview();
    private final ScenesReview scenes = new ScenesReview();

    public PlanningReview(TrustedDocuments documents) {
        this.research = new ResearchReview(documents);
    }

    public Validation review(ProductionStage stage, Object input, Object body) {
        return switch (stage) {
            case RESEARCH -> research.review((ResearchInput) input, cast(body, Research.class));
            case SCRIPT -> script.review((ScriptInput) input, cast(body, Script.class));
            case SCENES -> scenes.review((ScenesInput) input, cast(body, Scenes.class));
            default -> throw new IllegalArgumentException("Not a planning stage: " + stage);
        };
    }

    private static <T> T cast(Object body, Class<T> type) {
        if (body != null && !type.isInstance(body)) {
            throw new IllegalArgumentException("Unexpected " + type.getSimpleName() + " body");
        }
        return type.cast(body);
    }

    static boolean fakeRun(ResearchInput input) {
        InputVersion version = input.version();
        return version != null && version.providerVersion() != null
                && version.providerVersion().startsWith(FAKE_PROVIDER_PREFIX);
    }
}
