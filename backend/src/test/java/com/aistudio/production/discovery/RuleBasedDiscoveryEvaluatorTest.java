package com.aistudio.production.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;

/** R01 scoring and R02 duplicate rules on fixed fixtures, plus prohibited topics and unsupported claims. */
class RuleBasedDiscoveryEvaluatorTest {
    private final RuleBasedDiscoveryEvaluator evaluator = new RuleBasedDiscoveryEvaluator();
    private final Input input = DiscoveryFixtures.input(1, 5);

    private static final Candidate SKY = DiscoveryFixtures.candidate("Rayleigh scattering",
            "Why is the daytime sky blue instead of violet?", "Why the Sky Is Blue, Not Violet",
            "Walk through the scattering mechanism one step at a time with a simple diagram.");

    private static final String SKY_BRIEF = """
            Question: Why is the daytime sky blue instead of violet?
            Angle: Walk through the scattering mechanism one step at a time with a simple diagram.
            Core topic: Rayleigh scattering
            """;

    private EvaluatedCandidate first(List<CatalogEntry> catalog, Candidate candidate) {
        return evaluator.evaluate(input, DiscoveryFixtures.response(5, candidate), catalog).get(0);
    }

    // ------------------------------------------------------------------ R02 duplicates

    @Test
    void exactTopicTitleIsADuplicate() {
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "why the sky is blue,  NOT violet",
                "Something unrelated")), SKY);
        assertThat(e.blockCodes()).containsExactly(Block.DUPLICATE);
        assertThat(e.comparisons()).singleElement().satisfies(c -> {
            assertThat(c.topicId()).isEqualTo("t1");
            assertThat(c.contentId()).isEqualTo("c1");
            assertThat(c.revisionId()).isEqualTo("r1");
            assertThat(c.reason()).isEqualTo("EXACT_TITLE");
        });
        assertThat(e.eligible()).isFalse();
    }

    @Test
    void paraphraseWithSynonymsAndTheSameAngleIsADuplicate() {
        Candidate paraphrase = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why does the daytime atmosphere look azure rather than violet?", "The Azure Atmosphere Puzzle",
                "Walk through the scattering mechanism step by step with a simple diagram.");
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet",
                SKY_BRIEF)), paraphrase);
        assertThat(e.blockCodes()).containsExactly(Block.DUPLICATE);
        assertThat(e.comparisons()).singleElement().satisfies(c -> assertThat(c.reason()).contains("DUPLICATE"));
    }

    @Test
    void sameQuestionWithADistinctAngleIsHeldAsUncertain() {
        Candidate otherAngle = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why is the daytime sky blue instead of violet?", "Violet Light Is Hiding in Plain Sight",
                "Contrast the common belief about sunsets with how human eyes weigh colors.");
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet",
                SKY_BRIEF)), otherAngle);
        assertThat(e.blockCodes()).containsExactly(Block.SIMILARITY_UNCERTAIN);
    }

    @Test
    void borderlineQuestionWithAnUnclearAngleIsHeldAsUncertain() {
        Candidate borderline = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why is the daytime sky blue at noon?", "Noon Sky Blue Explained Simply",
                "Rayleigh scattering of the blue daytime sky.");
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet",
                SKY_BRIEF)), borderline);
        assertThat(e.blockCodes()).containsExactly(Block.SIMILARITY_UNCERTAIN);
    }

    @Test
    void sameFieldWithADifferentQuestionAndAngleIsAllowedAndRecordsNoBlock() {
        Candidate sunset = DiscoveryFixtures.candidate("Sunset colors",
                "Why do sunsets turn red and orange?", "Why Sunsets Turn Red",
                "Compare light paths at noon and at dusk against everyday distances.");
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet",
                SKY_BRIEF)), sunset);
        assertThat(e.blockCodes()).isEmpty();
        assertThat(e.eligible()).isTrue();
    }

    @Test
    void sharedVocabularyWithADifferentQuestionIsNotBlocked() {
        Candidate night = DiscoveryFixtures.candidate("Olbers paradox",
                "Why is the night sky dark if space is full of stars?", "Why the Night Sky Is Dark",
                "Use a what-if thought experiment about an endless forest of trees.");
        EvaluatedCandidate e = first(List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet",
                SKY_BRIEF)), night);
        assertThat(e.blockCodes()).isEmpty();
    }

    @Test
    void legacyBriefWithoutLabelsFallsBackToTitleAndBrief() {
        CatalogEntry legacy = DiscoveryFixtures.entry("2", "The blue sky",
                "Daytime sky blue instead of violet because of Rayleigh scattering");
        Candidate paraphrase = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why is the daytime sky blue instead of violet?", "Blue Not Violet",
                "Blue daytime sky instead of violet.");
        EvaluatedCandidate e = first(List.of(legacy), paraphrase);
        assertThat(e.blockCodes()).contains(Block.DUPLICATE);
    }

    @Test
    void repeatsInsideOneBatchBlockTheLaterCandidateOnly() {
        Candidate copy = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why is the daytime sky blue instead of violet?", "Blue Sky Mystery",
                "Compare it against familiar everyday scales.");
        Candidate paraphrase = DiscoveryFixtures.candidate("Rayleigh scattering",
                "Why does the daytime atmosphere look azure rather than violet?", "The Azure Atmosphere",
                "Walk through the scattering mechanism step by step with a simple diagram.");
        List<EvaluatedCandidate> list = evaluator.evaluate(input, DiscoveryFixtures.response(5, SKY, copy, paraphrase),
                List.of());
        assertThat(list.get(0).blockCodes()).isEmpty();
        assertThat(list.get(1).blockCodes()).containsExactly(Block.DUPLICATE);
        assertThat(list.get(1).comparisons()).singleElement()
                .satisfies(c -> assertThat(c.reason()).startsWith("BATCH_CANDIDATE_1 EXACT_REPEAT"));
        assertThat(list.get(2).blockCodes()).containsExactly(Block.DUPLICATE);
    }

    // ------------------------------------------------------------------ prohibited and unsupported

    @Test
    void channelAndBaselineProhibitedTopicsBlock() {
        Input channel = DiscoveryFixtures.input(1, 5, List.of("crypto currency", "politics"));
        Candidate crypto = DiscoveryFixtures.candidate("Mining energy",
                "How much energy does crypto currency mining use?", "The Energy Bill of Mining",
                "Compare it against familiar everyday scales.");
        Candidate weapons = DiscoveryFixtures.candidate("Ancient weapons physics",
                "How did a medieval trebuchet throw so far?", "Trebuchet Physics",
                "Walk through the weapons mechanism step by step.");
        List<EvaluatedCandidate> list = evaluator.evaluate(channel, DiscoveryFixtures.response(5, crypto, weapons),
                List.of());
        assertThat(list.get(0).blockCodes()).containsExactly(Block.CHANNEL_PROHIBITED);
        assertThat(list.get(1).blockCodes()).as("baseline 'weapon' matches the plural").containsExactly(
                Block.CHANNEL_PROHIBITED);
        assertThat(list.get(2).blockCodes()).isEmpty();
    }

    @Test
    void claimsWithoutApprovedEvidenceAreUnsupported() {
        Candidate claim = withAssertions(SKY, List.of(new Claim("Violet light scatters more than blue", null)));
        assertThat(first(List.of(), claim).blockCodes()).containsExactly(Block.UNSUPPORTED_ASSERTION);

        Candidate invented = withAssertions(SKY, List.of(new Claim("Violet light scatters more", "provider:ref-1")));
        assertThat(first(List.of(), invented).blockCodes()).as("a provider evidence ID is not approved")
                .containsExactly(Block.UNSUPPORTED_ASSERTION);

        RuleBasedDiscoveryEvaluator withApproved = new RuleBasedDiscoveryEvaluator(Set.of("ev-approved-1"));
        Candidate approved = withAssertions(SKY, List.of(new Claim("Violet light scatters more", "ev-approved-1")));
        assertThat(withApproved.evaluate(input, DiscoveryFixtures.response(5, approved), List.of()).get(0)
                .blockCodes()).isEmpty();
    }

    @Test
    void definitiveWordingAndCitedSourcesAreUnsupported() {
        Candidate definitive = new Candidate(SKY.coreTopic(), SKY.centralQuestion(),
                "Scientists Proved Why the Sky Is Blue", SKY.angle(), SKY.outline(), SKY.evidencePlan(), List.of(),
                SKY.suggestedScores(), SKY.rationale());
        assertThat(first(List.of(), definitive).blockCodes()).containsExactly(Block.UNSUPPORTED_ASSERTION);

        Candidate percent = new Candidate(SKY.coreTopic(), SKY.centralQuestion(), SKY.title(), SKY.angle(),
                List.of("Hook diagram", "Show that 90% of light is scattered"), SKY.evidencePlan(), List.of(),
                SKY.suggestedScores(), SKY.rationale());
        assertThat(first(List.of(), percent).blockCodes()).containsExactly(Block.UNSUPPORTED_ASSERTION);

        Candidate fakeSource = new Candidate(SKY.coreTopic(), SKY.centralQuestion(), SKY.title(), SKY.angle(),
                SKY.outline(), List.of(new EvidencePlan("PEER_REVIEWED_PAPER",
                        "See https://example.org/paper verified by Smith et al")), List.of(), SKY.suggestedScores(),
                SKY.rationale());
        assertThat(first(List.of(), fakeSource).blockCodes()).containsExactly(Block.UNSUPPORTED_ASSERTION);
    }

    // ------------------------------------------------------------------ R01 scores

    @Test
    void scoresUseFiveLevelsOfEachCriterionMaximumAndTotalAtMost100() {
        EvaluatedCandidate e = first(List.of(), SKY);
        Scores s = e.scores();
        assertThat(s.interest()).isIn(0, 6, 12, 18, 25);
        assertThat(s.channelFit()).isIn(0, 5, 10, 15, 20);
        assertThat(s.originality()).isIn(0, 5, 10, 15, 20);
        assertThat(s.visualPotential()).isIn(0, 3, 7, 11, 15);
        assertThat(s.evidencePotential()).isIn(0, 3, 7, 11, 15);
        assertThat(s.costEfficiency()).isIn(0, 1, 2, 3, 5);
        assertThat(e.totalScore()).isEqualTo(s.interest() + s.channelFit() + s.originality() + s.visualPotential()
                + s.evidencePotential() + s.costEfficiency()).isLessThanOrEqualTo(100);
        assertThat(e.totalScore()).as("a well-formed candidate on an empty catalog scores high").isGreaterThan(85);
        assertThat(e.rationale()).startsWith("rules v1: total " + e.totalScore() + "/100");
        assertThat(RuleBasedDiscoveryEvaluator.points(25, 3)).isEqualTo(18);
        assertThat(RuleBasedDiscoveryEvaluator.points(15, 1)).isEqualTo(3);
        assertThat(RuleBasedDiscoveryEvaluator.points(5, 4)).isEqualTo(5);
    }

    @Test
    void suggestedScoresAreOnlyOneSignal() {
        Candidate low = new Candidate(SKY.coreTopic(), SKY.centralQuestion(), SKY.title(), SKY.angle(), SKY.outline(),
                SKY.evidencePlan(), List.of(), new Scores(0, 0, 0, 0, 0, 0), SKY.rationale());
        Candidate inflated = new Candidate("Thing", "Thing?", "Thing", "Thing.", List.of("a", "b"),
                List.of(new EvidencePlan("BLOG", "thing")), List.of(), new Scores(25, 20, 20, 15, 15, 5), "r");
        EvaluatedCandidate lowScored = first(List.of(), low);
        EvaluatedCandidate inflatedScored = first(List.of(), inflated);
        assertThat(lowScored.totalScore()).isGreaterThan(60);
        assertThat(inflatedScored.totalScore()).isLessThan(lowScored.totalScore());
    }

    @Test
    void similarityLowersOriginality() {
        Candidate sunset = DiscoveryFixtures.candidate("Sunset colors", "Why do sunsets turn red and orange?",
                "Why Sunsets Turn Red", "Compare light paths at noon and at dusk against everyday distances.");
        int alone = first(List.of(), sunset).scores().originality();
        int near = first(List.of(DiscoveryFixtures.entry("9", "Sunset colors",
                "Question: Why do sunsets look red?\nAngle: Explain with paint mixing.")), sunset).scores().originality();
        assertThat(alone).isEqualTo(20);
        assertThat(near).isLessThan(alone);
    }

    @Test
    void rankingBreaksTiesByEvidenceThenOriginalityThenCandidateId() {
        Scores base = new Scores(18, 15, 10, 11, 7, 3);
        EvaluatedCandidate a = new EvaluatedCandidate(3, SKY, base, 64, List.of(), List.of(), "a");
        EvaluatedCandidate b = new EvaluatedCandidate(2, SKY, new Scores(18, 15, 5, 11, 11, 4), 64, List.of(),
                List.of(), "b");
        EvaluatedCandidate c = new EvaluatedCandidate(1, SKY, new Scores(18, 15, 15, 11, 7, -2), 64, List.of(),
                List.of(), "c");
        EvaluatedCandidate d = new EvaluatedCandidate(4, SKY, base, 64, List.of(), List.of(), "d");
        EvaluatedCandidate top = new EvaluatedCandidate(5, SKY, base, 70, List.of(Block.DUPLICATE), List.of(), "x");
        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(List.of(a, b, c, d, top));
        assertThat(ranked).extracting(EvaluatedCandidate::candidateId).containsExactly(2, 1, 3, 4);
        assertThat(DiscoverySelections.best(List.of(top))).isEmpty();
    }

    @Test
    void evaluationIsDeterministic() {
        Response response = new FakeDiscoveryProvider().generate(DiscoveryFixtures.input(77, 8));
        Input in = DiscoveryFixtures.input(77, 8);
        List<CatalogEntry> catalog = List.of(DiscoveryFixtures.entry("1", "Why the Sky Is Blue, Not Violet", SKY_BRIEF));
        assertThat(evaluator.evaluate(in, response, catalog)).isEqualTo(evaluator.evaluate(in, response, catalog));
    }

    @Test
    void invalidResponsesAreRejectedBeforeEvaluation() {
        Response wrongCount = DiscoveryFixtures.response(4);
        assertThatThrownBy(() -> evaluator.evaluate(input, wrongCount, List.of()))
                .isInstanceOf(DiscoveryResponseException.class);
    }

    private static Candidate withAssertions(Candidate c, List<Claim> claims) {
        return new Candidate(c.coreTopic(), c.centralQuestion(), c.title(), c.angle(), c.outline(), c.evidencePlan(),
                new ArrayList<>(claims), c.suggestedScores(), c.rationale());
    }
}
