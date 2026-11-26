package com.aistudio.production.stage;

import static com.aistudio.production.stage.Findings.blank;
import static com.aistudio.production.stage.Findings.list;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.Comparison;
import com.aistudio.production.contract.ProductionPlanning.Script;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Segment;
import com.aistudio.production.contract.ProductionPlanning.Timing;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.discovery.TextSimilarity;

/**
 * SCRIPT review (R03, R04). Required blocks: structure and claim-reference errors, language, a fact stated without
 * a linked claim, a number that is not in the linked claims, a hypothesis or scenario stated as fact, a substantive
 * repeat of a recent adopted script, and an unsuitable estimated length. Hook, ending, small length deviations and
 * near-duplicates with their own explanation are recommendations: the script still moves on to SCENES.
 *
 * <p>Local rules only check structure, references, timing and the phrasing patterns of {@link Statements}; they do
 * not prove the script is factually complete or semantically original (V2-05).
 */
public final class ScriptReview {
    static final int MAX_SEGMENTS = 30;
    static final int HOOK_MS = 3_000;
    static final double ESTIMATE_TOLERANCE = 0.10;
    static final int COMPARISON_CHARS = 600;
    private static final Pattern BRANDING = Pattern.compile(
            "(?i)[^.!?]*\\b(follow|subscribe|like and share)\\b[^.!?]*[.!?]?");

    public Validation review(ScriptInput input, Script script) {
        Findings findings = new Findings();
        if (script == null) {
            findings.block("SCRIPT_STRUCTURE", "script", List.of(), "Return a script.");
            return findings.validation();
        }
        if (blank(script.language()) || !script.language().equals(input.snapshot().language())) {
            findings.block("SCRIPT_LANGUAGE_MISMATCH", "script.language", List.of(String.valueOf(script.language())),
                    "Write the script in the channel language " + input.snapshot().language() + ".");
        }
        List<Segment> segments = list(script.segments());
        Map<String, Claim> claims = new HashMap<>();
        list(input.research().claims()).forEach(claim -> claims.putIfAbsent(claim.id(), claim));
        Set<String> core = new HashSet<>(list(input.research().scriptClaimIds()));
        if (!structure(script, segments, findings)) {
            return findings.validation();
        }
        Set<String> used = new HashSet<>();
        for (Segment segment : segments) {
            segment(segment, claims, core, used, findings);
        }
        for (String id : core) {
            if (!used.contains(id)) {
                findings.recommend("CLAIM_UNUSED", "script", List.of(id),
                        "A researched claim is not used; check the explanation is complete.");
            }
        }
        timing(script, segments, findings);
        hookAndEnding(script, segments, findings);
        repeats(input, script, segments, findings);
        if (blank(script.editorialIntent())) {
            findings.recommend("EDITORIAL_INTENT_MISSING", "script.editorialIntent", List.of(),
                    "Say what makes this explanation distinct and how it should end.");
        }
        return findings.validation();
    }

    private static boolean structure(Script script, List<Segment> segments, Findings findings) {
        Set<String> ids = new HashSet<>();
        boolean valid = !segments.isEmpty() && segments.size() <= MAX_SEGMENTS
                && segments.size() == (script.segments() == null ? 0 : script.segments().size());
        for (Segment segment : segments) {
            valid &= !blank(segment.id()) && ids.add(segment.id()) && !blank(segment.narration());
        }
        List<String> hook = list(script.hookSegmentIds());
        boolean leading = !hook.isEmpty() && hook.size() < Math.max(2, segments.size())
                && Set.copyOf(hook).size() == hook.size();
        for (int i = 0; leading && i < hook.size(); i++) {
            leading = hook.get(i).equals(segments.get(i).id());
        }
        if (!valid) {
            findings.block("SCRIPT_STRUCTURE", "script.segments", List.of(),
                    "Return 1 to " + MAX_SEGMENTS + " segments with unique ids and narration.");
        }
        if (valid && !leading) {
            findings.block("SCRIPT_STRUCTURE", "script.hookSegmentIds", hook,
                    "The hook must be the opening segment or segments, in order.");
        }
        return valid;
    }

    private static void segment(Segment segment, Map<String, Claim> claims, Set<String> core, Set<String> used,
            Findings findings) {
        String location = "script.segments[" + segment.id() + "]";
        List<Claim> linked = new ArrayList<>();
        for (String id : list(segment.claimIds())) {
            Claim claim = claims.get(id);
            if (claim == null) {
                findings.block("UNKNOWN_CLAIM_REFERENCE", location, List.of(id), "Reference only researched claims.");
            } else if (!core.contains(id)) {
                findings.block("EXCLUDED_CLAIM_REFERENCE", location, List.of(id),
                        "This claim was not cleared for the script; remove it.");
            } else {
                linked.add(claim);
                used.add(id);
            }
        }
        boolean fact = linked.stream().anyMatch(claim -> claim.kind() == ClaimKind.FACT);
        Set<String> allowedNumbers = new HashSet<>();
        linked.forEach(claim -> allowedNumbers.addAll(Statements.numbers(claim.text())));
        boolean causalClaim = linked.stream().anyMatch(claim -> Statements.causal(claim.text()));
        List<String> refs = linked.stream().map(Claim::id).toList();
        for (Statements.Sentence sentence : Statements.sentences(segment.narration())) {
            if (linked.isEmpty() && sentence.assertsFact()) {
                findings.block("UNSUPPORTED_ASSERTION", location, List.of(),
                        "States a fact without a researched claim: \"" + sentence.text() + "\"");
                continue;
            }
            if (!linked.isEmpty() && !fact && !sentence.question() && !sentence.hedged()) {
                // only hypotheses or scenarios back this segment: every statement must stay framed as uncertain
                findings.block("HYPOTHESIS_AS_FACT", location, refs,
                        "Frame the hypothesis or scenario as uncertain: \"" + sentence.text() + "\"");
                continue;
            }
            Set<String> numbers = new LinkedHashSet<>(Statements.numbers(sentence.text()));
            numbers.removeAll(allowedNumbers);
            if (!linked.isEmpty() && !numbers.isEmpty()) {
                findings.block("NUMBER_MISMATCH", location, refs,
                        "Numbers " + numbers + " do not appear in the linked claims.");
            }
            if (fact && sentence.causal() && !sentence.question() && !sentence.hedged() && !causalClaim) {
                findings.block("UNSUPPORTED_CAUSAL_CLAIM", location, refs,
                        "The cause stated here is not part of the linked claims: \"" + sentence.text() + "\"");
            }
        }
    }

    private static void timing(Script script, List<Segment> segments, Findings findings) {
        int estimate = SpeechTiming.estimateMs(segments);
        if (!SpeechTiming.RULE_VERSION.equals(script.timingRuleVersion())) {
            findings.block("TIMING_RULE_MISMATCH", "script.timingRuleVersion",
                    List.of(String.valueOf(script.timingRuleVersion())), "Use " + SpeechTiming.RULE_VERSION + ".");
        }
        if (Math.abs(script.estimatedDurationMs() - estimate) > estimate * ESTIMATE_TOLERANCE) {
            findings.block("DURATION_ESTIMATE_MISMATCH", "script.estimatedDurationMs",
                    List.of(Integer.toString(script.estimatedDurationMs()), Integer.toString(estimate)),
                    "The declared length differs from the server estimate by more than 10%.");
        }
        duration(estimate, findings);
    }

    /** The common duration contract on the server estimate: 40-60 s normal, 35-65 s adjustable, else blocked. */
    static void duration(int estimate, Findings findings) {
        Timing timing = ProductionPlanning.timing(estimate);
        if (timing == Timing.UNSUITABLE) {
            findings.block("DURATION_UNSUITABLE", "script.estimatedDurationMs", List.of(Integer.toString(estimate)),
                    "Estimated speech must be between 35 and 65 seconds (target 40 to 60).");
        } else if (timing == Timing.ADJUSTMENT_RECOMMENDED) {
            findings.recommend("DURATION_ADJUSTMENT", "script.estimatedDurationMs",
                    List.of(Integer.toString(estimate)),
                    "Adjust pace or pauses toward 40 to 60 seconds; do not pad with extra sentences.");
        }
    }

    private static void hookAndEnding(Script script, List<Segment> segments, Findings findings) {
        List<Segment> hook = segments.stream().filter(segment -> list(script.hookSegmentIds())
                .contains(segment.id())).toList();
        String hookText = hook.stream().map(Segment::narration).collect(Collectors.joining(" "));
        if (SpeechTiming.estimateMs(hook) > HOOK_MS) {
            findings.recommend("HOOK_TOO_LONG", "script.hookSegmentIds", list(script.hookSegmentIds()),
                    "Land the hook within the first 3 seconds.");
        }
        String lower = hookText.toLowerCase(java.util.Locale.ROOT);
        if (!hookText.contains("?") && !lower.matches("(?s).*\\b(imagine|what if|picture)\\b.*")
                && Statements.numbers(hookText).isEmpty()) {
            findings.recommend("HOOK_WEAK", "script.hookSegmentIds", list(script.hookSegmentIds()),
                    "Open with a question, a surprising number or a what-if.");
        }
        Segment last = segments.getLast();
        if (list(script.hookSegmentIds()).contains(last.id()) || last.narration().trim().split("\\s+").length < 6) {
            findings.recommend("ENDING_WEAK", "script.segments[" + last.id() + "]", List.of(),
                    "End with a memorable payoff or an open question.");
        }
    }

    /**
     * Substantive repeat: the same question and the same explanation as a recent adopted script, measured on
     * concepts (synonym-mapped words) and on the numbers used, so a reworded copy is still caught. A script that
     * shares the subject but asks a different question is allowed with a recommendation. Channel branding is
     * removed first so the shared outro alone never blocks.
     */
    private static void repeats(ScriptInput input, Script script, List<Segment> segments, Findings findings) {
        String hook = segments.stream().filter(segment -> list(script.hookSegmentIds()).contains(segment.id()))
                .map(Segment::narration).collect(Collectors.joining(" "));
        String body = excerpt(segments.stream().map(Segment::narration).collect(Collectors.joining(" ")));
        List<String> question = TextSimilarity.concepts(unbranded(script.editorialIntent() + " " + hook));
        List<String> explanation = TextSimilarity.concepts(unbranded(body));
        Set<String> numbers = Statements.numbers(body);
        for (Comparison recent : list(input.recentScripts())) {
            String reference = recent.ref() == null ? "recent" : "operation:" + recent.ref().operationId();
            double q = TextSimilarity.jaccard(question,
                    TextSimilarity.concepts(unbranded(recent.centralClaim() + " " + recent.hook())));
            double b = TextSimilarity.jaccard(explanation, TextSimilarity.concepts(unbranded(recent.structure())));
            double n = numberOverlap(numbers, Statements.numbers(recent.structure()));
            if (q >= 0.5 && (b >= 0.45 || n >= 0.75)) {
                findings.block("SUBSTANTIVE_REPEAT", "script", List.of(reference),
                        "Repeats the question and explanation of a recent script (question %.2f, explanation %.2f, numbers %.2f)."
                                .formatted(q, b, n));
            } else if (q >= 0.5 || b >= 0.35 || n >= 0.75) {
                findings.recommend("SIMILAR_RECENT_SCRIPT", "script", List.of(reference),
                        "Close to a recent script (question %.2f, explanation %.2f, numbers %.2f); keep the distinct angle clear."
                                .formatted(q, b, n));
            }
        }
    }

    private static double numberOverlap(Set<String> a, Set<String> b) {
        if (a.size() < 2 || b.size() < 2) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static String unbranded(String text) {
        return text == null ? "" : BRANDING.matcher(text).replaceAll(" ");
    }

    private static String excerpt(String text) {
        int points = text.codePointCount(0, text.length());
        return text.substring(0, text.offsetByCodePoints(0, Math.min(points, COMPARISON_CHARS)));
    }
}
