package com.aistudio.production.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionSelectionPort.Selection;

/** Ranking, re-check merge and the selection value handed to {@code ProductionSelectionPort}. */
public final class DiscoverySelections {
    /** Higher total first, then evidence, then originality, then the stable candidate ID (R01). */
    public static final Comparator<EvaluatedCandidate> RANKING = Comparator
            .comparingInt(EvaluatedCandidate::totalScore).reversed()
            .thenComparing(Comparator.comparingInt((EvaluatedCandidate e) -> e.scores().evidencePotential())
                    .reversed())
            .thenComparing(Comparator.comparingInt((EvaluatedCandidate e) -> e.scores().originality()).reversed())
            .thenComparingInt(EvaluatedCandidate::candidateId);

    private DiscoverySelections() {}

    public static List<EvaluatedCandidate> ranked(List<EvaluatedCandidate> evaluations) {
        return evaluations.stream().filter(EvaluatedCandidate::eligible).sorted(RANKING).toList();
    }

    public static Optional<EvaluatedCandidate> best(List<EvaluatedCandidate> evaluations) {
        return ranked(evaluations).stream().findFirst();
    }

    /** The stored response, rebuilt in candidate-ID order from a stored evaluation. */
    public static Response response(List<EvaluatedCandidate> evaluations) {
        List<EvaluatedCandidate> ordered = evaluations.stream()
                .sorted(Comparator.comparingInt(EvaluatedCandidate::candidateId)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).candidateId() != i + 1) {
                throw new IllegalStateException("Stored candidate IDs are not 1..n");
            }
        }
        return new Response(DiscoveryRules.RESPONSE_VERSION, ordered.stream().map(EvaluatedCandidate::candidate)
                .toList());
    }

    /**
     * Re-check at SELECT time: a candidate blocked before stays blocked with its earlier codes; a fresh block from
     * the latest catalog is added. Scores follow the fresh evaluation.
     */
    public static List<EvaluatedCandidate> merge(List<EvaluatedCandidate> stored, List<EvaluatedCandidate> fresh) {
        Map<Integer, EvaluatedCandidate> earlier = new LinkedHashMap<>();
        stored.forEach(e -> earlier.put(e.candidateId(), e));
        List<EvaluatedCandidate> merged = new ArrayList<>();
        for (EvaluatedCandidate now : fresh) {
            EvaluatedCandidate before = earlier.get(now.candidateId());
            if (before == null || before.eligible()) {
                merged.add(now);
                continue;
            }
            List<Block> blocks = new ArrayList<>(before.blockCodes());
            now.blockCodes().stream().filter(b -> !blocks.contains(b)).forEach(blocks::add);
            merged.add(new EvaluatedCandidate(now.candidateId(), now.candidate(), now.scores(), now.totalScore(),
                    List.copyOf(blocks), now.comparisons(), now.rationale()));
        }
        return List.copyOf(merged);
    }

    public static String rationale(Mode mode, EvaluatedCandidate chosen, int eligibleCount) {
        String head = mode == Mode.AUTO
                ? "AUTO: highest ranked of " + eligibleCount + " eligible candidates after the SELECT re-check"
                : "MANUAL: candidate chosen by the requester and still eligible after the SELECT re-check";
        String text = head + "; candidate " + chosen.candidateId() + " scored " + chosen.totalScore() + "/100; "
                + chosen.rationale();
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    /** Selection value with a fingerprint over request, candidate, response hash, rules and normalized fields. */
    public static Selection selection(Input input, EvaluatedCandidate chosen, String responseHash) {
        Candidate c = chosen.candidate();
        String brief = brief(input, chosen);
        int duration = (input.minDurationSeconds() + input.maxDurationSeconds()) / 2;
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("v", 1);
        identity.put("requestId", input.requestId());
        identity.put("candidateId", chosen.candidateId());
        identity.put("responseHash", responseHash);
        identity.put("rulesVersion", input.rulesVersion());
        identity.put("title", TextSimilarity.normalized(c.title()));
        identity.put("brief", brief);
        identity.put("duration", duration);
        return new Selection(CanonicalJson.sha256(identity), c.title(), brief, duration, input.language());
    }

    /** Labelled brief. Later discovery runs read the Question and Angle lines back for comparison. */
    public static String brief(Input input, EvaluatedCandidate chosen) {
        Candidate c = chosen.candidate();
        StringBuilder text = new StringBuilder();
        text.append("Question: ").append(c.centralQuestion()).append('\n');
        text.append("Angle: ").append(c.angle()).append('\n');
        text.append("Core topic: ").append(c.coreTopic()).append('\n');
        text.append("Outline:\n");
        c.outline().forEach(beat -> text.append("- ").append(beat).append('\n'));
        text.append("Evidence plan:\n");
        for (EvidencePlan plan : c.evidencePlan()) {
            text.append("- [").append(plan.sourceType()).append("] ").append(plan.searchQuestion()).append('\n');
        }
        text.append("Discovery: request ").append(input.requestId()).append(", candidate ")
                .append(chosen.candidateId()).append(", rules v").append(input.rulesVersion());
        return text.toString();
    }
}
