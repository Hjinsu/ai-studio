package com.aistudio.production.stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionPlanning.Comparison;
import com.aistudio.production.contract.ProductionPlanning.Finding;
import com.aistudio.production.contract.ProductionPlanning.InputVersion;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.ResultRef;
import com.aistudio.production.contract.ProductionPlanning.Scene;
import com.aistudio.production.contract.ProductionPlanning.ScenePlan;
import com.aistudio.production.contract.ProductionPlanning.Scenes;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Severity;
import com.aistudio.production.contract.ProductionPlanning.Snapshot;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.stage.fake.FakePlanningProvider;
import com.aistudio.production.stage.fake.FakeResearchEvidenceProvider;
import com.aistudio.production.stage.fake.FixtureDocuments;
import com.aistudio.production.stage.fake.PlanningFixtures;

/** Builds fixed inputs the way the planning adapter does, and runs the FAKE provider and the review offline. */
final class PlanningTestSupport {
    static final String NEUTRON = "How heavy would a teaspoon of neutron star be?";
    static final String VENUS = "Why is a day on Venus longer than its year?";
    static final String TARDIGRADE = "How do tardigrades survive being completely dried out?";
    static final InputVersion FAKE_VERSION = new InputVersion("planning-prompt-1", "planning-rules-1",
            "fake-planning-1");
    static final String HASH = "a".repeat(64);

    static final PlanningFixtures FIXTURES = PlanningFixtures.bundled();
    static final FakePlanningProvider PROVIDER = new FakePlanningProvider(FIXTURES,
            new FakeResearchEvidenceProvider(FIXTURES));
    static final PlanningReview REVIEW = new PlanningReview(new FixtureDocuments(FIXTURES));

    private PlanningTestSupport() {}

    static Snapshot snapshot() {
        return new Snapshot(1, 2, 3, HASH, 4, HASH, "en", "Curious science for teens", "Teens and adults",
                "Energetic, clear", "Clean infographic", 50);
    }

    static ResearchInput researchInput(String question) {
        return researchInput(question, FAKE_VERSION);
    }

    static ResearchInput researchInput(String question, InputVersion version) {
        return new ResearchInput(snapshot(), version, 1, HASH, question,
                "Compare the effect against familiar everyday scales to make it intuitive.",
                List.of("Hook: pose the question", "Close with one open question"),
                List.of(new EvidencePlan("TEXTBOOK", "What do current sources say about " + question + "?")),
                List.of());
    }

    static ScriptInput scriptInput(Research research, List<Comparison> recent) {
        return new ScriptInput(snapshot(), FAKE_VERSION, new ResultRef(10, ProductionStage.RESEARCH, HASH),
                research, recent);
    }

    static ScenesInput scenesInput(Script script) {
        return new ScenesInput(snapshot(), FAKE_VERSION, new ResultRef(11, ProductionStage.SCRIPT, HASH), script);
    }

    /** What the planning adapter stores: narration and subtitle text derived from the fixed script. */
    static Scenes materialize(Script script, ScenePlan plan) {
        Map<String, String> narration = new LinkedHashMap<>();
        script.segments().forEach(segment -> narration.put(segment.id(), segment.narration()));
        return new Scenes(plan.visualStyle(), plan.scenes().stream().map(draft -> {
            String words = draft.segmentIds().stream().map(id -> narration.getOrDefault(id, ""))
                    .collect(Collectors.joining(" "));
            return new Scene(draft.id(), draft.order(), draft.segmentIds(), draft.estimatedDurationMs(), words,
                    draft.visualDescription(), draft.imagePrompt(), draft.direction(), draft.transition(), words);
        }).toList());
    }

    /** The comparison record the adapter builds from a recent adopted script. */
    static Comparison comparison(long operationId, Script script) {
        String hook = script.segments().stream().filter(segment -> script.hookSegmentIds().contains(segment.id()))
                .map(Segment::narration).collect(Collectors.joining(" "));
        String all = script.segments().stream().map(Segment::narration).collect(Collectors.joining(" "));
        return new Comparison(new ResultRef(operationId, ProductionStage.SCRIPT, HASH), excerpt(hook),
                excerpt(script.editorialIntent()), excerpt(all), excerpt(script.segments().getLast().narration()));
    }

    private static String excerpt(String text) {
        return text.substring(0, Math.min(text.length(), 600));
    }

    record Run(ResearchInput researchInput, Research research, Validation researchReview, ScriptInput scriptInput,
            Script script, Validation scriptReview, ScenesInput scenesInput, Scenes scenes, Validation scenesReview) {}

    static Run run(String question, List<Comparison> recent) {
        ResearchInput researchInput = researchInput(question);
        Research research = PROVIDER.research(researchInput);
        Validation researchReview = REVIEW.review(ProductionStage.RESEARCH, researchInput, research);
        ScriptInput scriptInput = scriptInput(research, recent);
        Script script = PROVIDER.writeScript(scriptInput);
        Validation scriptReview = REVIEW.review(ProductionStage.SCRIPT, scriptInput, script);
        ScenesInput scenesInput = scenesInput(script);
        Scenes scenes = materialize(script, PROVIDER.planScenes(scenesInput));
        Validation scenesReview = REVIEW.review(ProductionStage.SCENES, scenesInput, scenes);
        return new Run(researchInput, research, researchReview, scriptInput, script, scriptReview, scenesInput,
                scenes, scenesReview);
    }

    static List<String> blocks(Validation validation) {
        return codes(validation, Severity.BLOCK);
    }

    static List<String> recommendations(Validation validation) {
        return codes(validation, Severity.RECOMMENDATION);
    }

    private static List<String> codes(Validation validation, Severity severity) {
        return validation.findings().stream().filter(finding -> finding.severity() == severity)
                .map(Finding::code).toList();
    }
}
