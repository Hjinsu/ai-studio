package com.aistudio.production.stage.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.Investigation;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.SceneDraft;
import com.aistudio.production.contract.ProductionPlanning.ScenePlan;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ResearchEvidenceProvider;
import com.aistudio.production.stage.SpeechTiming;

/**
 * Deterministic FAKE planning provider (V2-03). It is only called by the planning adapter after the cost port
 * committed dispatch permission, and makes no network call. Research reuses the selected topic's question, angle
 * and investigation plan, takes documents and claim assessments from {@link ResearchEvidenceProvider}, and keeps
 * hypotheses and scenarios apart from facts. A question without an offline fixture returns only unverified claims,
 * which the review blocks as insufficient evidence. Script and scenes come from the same fixture topic, so
 * different topics get different structures; their lengths use {@link SpeechTiming}.
 */
public final class FakePlanningProvider implements PlanningProvider {
    static final String LANGUAGE = "en";
    static final String FRAMING = "vertical frame, key subject inside the center safe area";
    private static final int MAX_QUERIES = 4;

    private final PlanningFixtures fixtures;
    private final ResearchEvidenceProvider evidence;

    public FakePlanningProvider(PlanningFixtures fixtures, ResearchEvidenceProvider evidence) {
        this.fixtures = fixtures;
        this.evidence = evidence;
    }

    @Override
    public Research research(ResearchInput input) {
        List<String> queries = input.investigationPlan().stream().map(plan -> plan.searchQuestion())
                .limit(MAX_QUERIES).toList();
        List<ResearchEvidenceProvider.Document> documents = evidence.retrieve(input.question(), queries);
        Optional<PlanningFixtures.Topic> topic = fixtures.forQuestion(input.question());
        List<Investigation> investigations = new ArrayList<>();
        for (int i = 0; i < input.investigationPlan().size(); i++) {
            var plan = input.investigationPlan().get(i);
            investigations.add(new Investigation("i" + (i + 1), plan.searchQuestion(), plan.sourceType(),
                    documents.isEmpty() ? "NO_OFFLINE_DOCUMENT" : "ANSWERED_FROM_FIXTURE"));
        }
        List<Claim> claims = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<String> scriptClaimIds = List.of();
        if (topic.isPresent() && !documents.isEmpty()) {
            for (PlanningFixtures.Claim spec : topic.get().claims()) {
                Claim draft = new Claim(spec.id(), spec.kind(), spec.text(), List.of(), spec.uncertainty());
                claims.add(new Claim(spec.id(), spec.kind(), spec.text(), evidence.verify(draft, documents),
                        spec.uncertainty()));
            }
            limitations.addAll(topic.get().limitations());
            scriptClaimIds = topic.get().scriptClaimIds();
        } else {
            limitations.add("The offline FAKE library has no documents for this question; nothing was looked up.");
        }
        int next = claims.size() + 1;
        for (String assertion : input.unverifiedClaims()) {
            claims.add(new Claim("u" + next++, ClaimKind.UNVERIFIED, assertion, List.of(),
                    "Stated during discovery and not confirmed by a held document."));
        }
        if (claims.isEmpty()) {
            claims.add(new Claim("u1", ClaimKind.UNVERIFIED, "No held document answers: " + input.question(),
                    List.of(), "No evidence available offline."));
        }
        List<Source> sources = documents.stream().map(ResearchEvidenceProvider.Document::source).toList();
        return new Research(input.question(), input.angle(), investigations, claims, sources, limitations,
                scriptClaimIds);
    }

    @Override
    public Script writeScript(ScriptInput input) {
        Optional<PlanningFixtures.Topic> topic = fixtures.forQuestion(input.research().question());
        if (topic.isEmpty()) {
            List<Segment> fallback = List.of(new Segment("s1",
                    "No offline fixture script is available for this question.", List.of()));
            return new Script(LANGUAGE, fallback, List.of("s1"), SpeechTiming.estimateMs(fallback),
                    SpeechTiming.RULE_VERSION, "");
        }
        List<Segment> segments = topic.get().segments().stream()
                .map(segment -> new Segment(segment.id(), segment.narration(), segment.claimIds())).toList();
        return new Script(LANGUAGE, segments, topic.get().hookSegmentIds(), SpeechTiming.estimateMs(segments),
                SpeechTiming.RULE_VERSION, topic.get().editorialIntent());
    }

    @Override
    public ScenePlan planScenes(ScenesInput input) {
        Script script = input.script();
        Map<String, Segment> segments = new LinkedHashMap<>();
        script.segments().forEach(segment -> segments.put(segment.id(), segment));
        Optional<PlanningFixtures.Topic> topic = fixtures.topics().stream()
                .filter(candidate -> sameSegments(candidate, script)).findFirst();
        List<PlanningFixtures.Scene> specs = topic.map(PlanningFixtures.Topic::scenes)
                .orElseGet(() -> generic(script));
        String channelStyle = input.snapshot().visualStyle();
        String style = topic.map(PlanningFixtures.Topic::visualStyle).orElse("Simple explanatory illustration");
        String visualStyle = (channelStyle == null || channelStyle.isBlank() ? "" : channelStyle.trim() + "; ")
                + style + "; " + FRAMING;
        List<SceneDraft> drafts = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < specs.size(); i++) {
            var spec = specs.get(i);
            int duration = spec.segmentIds().stream().map(segments::get)
                    .mapToInt(segment -> segment == null ? 0 : SpeechTiming.estimateMs(segment.narration())).sum();
            if (i == specs.size() - 1) {
                duration = script.estimatedDurationMs() - total; // scene lengths add up to the script estimate
            }
            total += duration;
            drafts.add(new SceneDraft("scene-" + (i + 1), i + 1, spec.segmentIds(), duration,
                    spec.visualDescription(), spec.imagePrompt(), spec.direction(), spec.transition()));
        }
        return new ScenePlan(visualStyle, drafts);
    }

    private static boolean sameSegments(PlanningFixtures.Topic topic, Script script) {
        return topic.segments().stream().map(PlanningFixtures.Segment::narration).toList()
                .equals(script.segments().stream().map(Segment::narration).toList());
    }

    private static List<PlanningFixtures.Scene> generic(Script script) {
        return script.segments().stream().map(segment -> new PlanningFixtures.Scene(List.of(segment.id()),
                "Simple illustration of the narrated idea.",
                "Clean explanatory illustration of: " + firstWords(segment.narration()) + ", vertical",
                "Static medium shot, soft key light.", "Cut")).collect(Collectors.toList());
    }

    private static String firstWords(String text) {
        String[] words = text.replaceAll("[0-9]", "").trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 8))).trim();
    }
}
