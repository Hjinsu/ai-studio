package com.aistudio.production.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionSelectionPort.Selection;

class DiscoverySelectionsTest {
    private final Input input = DiscoveryFixtures.input(12, 8);
    private final Response response = new FakeDiscoveryProvider().generate(input);
    private final List<EvaluatedCandidate> evaluated = new RuleBasedDiscoveryEvaluator().evaluate(input, response,
            List.of());

    @Test
    void storedEvaluationRebuildsTheSameResponseAndHash() {
        Response rebuilt = DiscoverySelections.response(List.copyOf(evaluated.reversed()));
        assertThat(rebuilt).isEqualTo(response);
        assertThat(DiscoveryResponseCodec.sha256(rebuilt)).isEqualTo(DiscoveryResponseCodec.sha256(response));
    }

    @Test
    void selectionIsDeterministicAndBoundToCandidateAndResponse() {
        EvaluatedCandidate best = DiscoverySelections.best(evaluated).orElseThrow();
        String hash = DiscoveryResponseCodec.sha256(response);
        Selection one = DiscoverySelections.selection(input, best, hash);
        Selection two = DiscoverySelections.selection(input, best, hash);
        assertThat(one).isEqualTo(two);
        assertThat(one.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(one.title()).isEqualTo(best.candidate().title());
        assertThat(one.targetDurationSeconds()).isEqualTo(50);
        assertThat(one.brief()).startsWith("Question: " + best.candidate().centralQuestion() + "\nAngle: ")
                .contains("Evidence plan:").hasSizeLessThanOrEqualTo(4000);
        assertThat(DiscoverySelections.selection(input, best, "0".repeat(64)).fingerprint())
                .isNotEqualTo(one.fingerprint());
        EvaluatedCandidate other = evaluated.stream().filter(e -> e.candidateId() != best.candidateId()).findFirst()
                .orElseThrow();
        assertThat(DiscoverySelections.selection(input, other, hash).fingerprint()).isNotEqualTo(one.fingerprint());
    }

    @Test
    void recheckKeepsEarlierBlocksAndAddsNewOnes() {
        EvaluatedCandidate first = evaluated.get(0);
        EvaluatedCandidate blockedBefore = new EvaluatedCandidate(first.candidateId(), first.candidate(),
                first.scores(), first.totalScore(), List.of(Block.CHANNEL_PROHIBITED), List.of(), "old");
        EvaluatedCandidate second = evaluated.get(1);
        EvaluatedCandidate blockedNow = new EvaluatedCandidate(second.candidateId(), second.candidate(),
                second.scores(), second.totalScore(), List.of(Block.DUPLICATE), List.of(), "new");
        List<EvaluatedCandidate> stored = new java.util.ArrayList<>(evaluated);
        stored.set(0, blockedBefore);
        List<EvaluatedCandidate> fresh = new java.util.ArrayList<>(evaluated);
        fresh.set(1, blockedNow);
        List<EvaluatedCandidate> merged = DiscoverySelections.merge(stored, fresh);
        assertThat(merged.get(0).blockCodes()).containsExactly(Block.CHANNEL_PROHIBITED);
        assertThat(merged.get(1).blockCodes()).containsExactly(Block.DUPLICATE);
        assertThat(merged.subList(2, merged.size())).allSatisfy(e -> assertThat(e.eligible()).isTrue());
    }

    @Test
    void rationaleNamesModeScoreAndFitsTheColumn() {
        EvaluatedCandidate best = DiscoverySelections.best(evaluated).orElseThrow();
        assertThat(DiscoverySelections.rationale(Mode.AUTO, best, 8)).startsWith("AUTO: highest ranked of 8")
                .contains("scored " + best.totalScore() + "/100").hasSizeLessThanOrEqualTo(2000);
        assertThat(DiscoverySelections.rationale(Mode.MANUAL, best, 3)).startsWith("MANUAL:");
    }

    @Test
    void textSimilarityNormalizesSynonymsAndStems() {
        assertThat(TextSimilarity.concepts("Why does the SKY look Azure?")).containsExactly("atmosphere",
                "blue");
        assertThat(TextSimilarity.jaccard(TextSimilarity.concepts("why is the sky blue"),
                TextSimilarity.concepts("why does the atmosphere appear azure"))).isGreaterThan(0.6);
        assertThat(TextSimilarity.containsPhrase("Ancient Weapons physics", "weapon")).isTrue();
        assertThat(TextSimilarity.containsPhrase("weaponless", "weapon")).isFalse();
        assertThat(TextSimilarity.bigrams("blue sky light")).containsExactly("blue atmosphere", "atmosphere light");
        assertThat(TextSimilarity.jaccard(List.of(), List.of())).isZero();
    }
}
