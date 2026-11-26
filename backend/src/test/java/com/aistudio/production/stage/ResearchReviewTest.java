package com.aistudio.production.stage;

import static com.aistudio.production.stage.PlanningTestSupport.NEUTRON;
import static com.aistudio.production.stage.PlanningTestSupport.REVIEW;
import static com.aistudio.production.stage.PlanningTestSupport.blocks;
import static com.aistudio.production.stage.PlanningTestSupport.recommendations;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.ClaimKind;
import com.aistudio.production.contract.ProductionPlanning.EvidenceRef;
import com.aistudio.production.contract.ProductionPlanning.Relation;
import com.aistudio.production.contract.ProductionPlanning.Research;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ProductionPlanning.SourceOrigin;
import com.aistudio.production.contract.ProductionPlanning.SourceStatus;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionStage;

/** RESEARCH review: source lookup status, claim support and core-evidence blocking (R01, R02). */
class ResearchReviewTest {
    private final ResearchInput input = PlanningTestSupport.researchInput(NEUTRON);
    private final Research base = PlanningTestSupport.PROVIDER.research(input);

    private Validation review(Research research) {
        return REVIEW.review(ProductionStage.RESEARCH, input, research);
    }

    private Research withClaims(UnaryOperator<List<Claim>> change) {
        return new Research(base.question(), base.angle(), base.investigations(),
                change.apply(new ArrayList<>(base.claims())), base.sources(), base.limitations(),
                base.scriptClaimIds());
    }

    private Research withSources(UnaryOperator<List<Source>> change) {
        return new Research(base.question(), base.angle(), base.investigations(), base.claims(),
                change.apply(new ArrayList<>(base.sources())), base.limitations(), base.scriptClaimIds());
    }

    private static Claim claim(List<Claim> claims, String id) {
        return claims.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Claim> replace(List<Claim> claims, Claim changed) {
        claims.replaceAll(c -> c.id().equals(changed.id()) ? changed : c);
        return claims;
    }

    private static Source replaceUrl(Source s, String url) {
        return new Source(s.id(), s.origin(), s.status(), url, s.title(), s.publisher(), s.checkedAtUtc(),
                s.documentHash());
    }

    @Test
    void oneSufficientSourceIsEnoughAndCountsAreNotEnforced() {
        // keep a single document and a single core fact supported by it
        Source only = base.sources().getFirst();
        Claim fact = claim(base.claims(), "c1");
        Research single = new Research(base.question(), base.angle(), base.investigations().subList(0, 1),
                List.of(fact), List.of(only), List.of(), List.of("c1"));
        Validation validation = review(single);
        assertThat(validation.blocked()).isFalse();
        assertThat(blocks(validation)).isEmpty();
    }

    @Test
    void fabricatedFixtureSourceIsBlocked() {
        var research = withSources(sources -> {
            sources.replaceAll(s -> s.id().endsWith(":d2") ? new Source(s.id(), s.origin(), s.status(),
                    "https://fixtures.ai-studio.invalid/neutron-star/made-up", "Made up paper", s.publisher(),
                    null, "b".repeat(64)) : s);
            return sources;
        });
        assertThat(blocks(review(research))).contains("SOURCE_NOT_FOUND", "CORE_EVIDENCE_INSUFFICIENT");
    }

    @Test
    void urlThatDoesNotMatchTheHeldDocumentIsBlocked() {
        var research = withSources(sources -> {
            sources.replaceAll(s -> s.id().endsWith(":d1")
                    ? replaceUrl(s, "https://fixtures.ai-studio.invalid/neutron-star/other") : s);
            return sources;
        });
        assertThat(blocks(review(research))).contains("SOURCE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = { "ftp://example.org/paper", "http://localhost/paper", "http://127.0.0.1/paper",
            "http://192.168.0.10/paper", "https://user:pw@example.org/paper", "not a url", "http://intranet.local/x",
            "http://[::1]/x" })
    void unsafeOrMalformedUrlsAreBlocked(String url) {
        var research = withSources(sources -> {
            sources.add(new Source("hint", SourceOrigin.MODEL_HINT, SourceStatus.UNVERIFIED, url, "Hint", null, null,
                    null));
            return sources;
        });
        assertThat(blocks(review(research))).containsExactly("SOURCE_URL_INVALID");
        assertThat(ResearchReview.validUrl("https://www.example.org/a", false)).isTrue();
        assertThat(ResearchReview.validUrl("https://fixtures.ai-studio.invalid/a", false)).isFalse();
    }

    @Test
    void quoteThatIsNotInTheDocumentIsBlocked() {
        var research = withClaims(claims -> {
            Claim c3 = claim(claims, "c3");
            EvidenceRef ref = c3.evidenceRefs().getFirst();
            return replace(claims, new Claim(c3.id(), c3.kind(), c3.text(), List.of(new EvidenceRef(ref.sourceId(),
                    ref.locator(), "a sugar cube would weigh about a trillion tons", Relation.SUPPORTS)),
                    c3.uncertainty()));
        });
        assertThat(blocks(review(research))).contains("QUOTE_NOT_FOUND", "CORE_EVIDENCE_INSUFFICIENT");
    }

    @Test
    void verificationFlagsThatTheServerDidNotIssueAreBlocked() {
        var fixtureMarkedVerified = withSources(sources -> {
            sources.replaceAll(s -> new Source(s.id(), s.origin(), SourceStatus.VERIFIED, s.url(), s.title(),
                    s.publisher(), "2026-11-26T00:00:00Z", s.documentHash()));
            return sources;
        });
        Validation fixture = review(fixtureMarkedVerified);
        assertThat(blocks(fixture)).contains("SOURCE_FALSE_VERIFICATION", "CORE_EVIDENCE_INSUFFICIENT");

        var modelHintVerified = withSources(sources -> {
            sources.add(new Source("hint", SourceOrigin.MODEL_HINT, SourceStatus.VERIFIED, "https://example.org/x",
                    "Claimed paper", "Someone", "2026-11-26T00:00:00Z", "c".repeat(64)));
            return sources;
        });
        assertThat(blocks(review(modelHintVerified))).containsExactly("SOURCE_FALSE_VERIFICATION");

        var fakeMarkedFixture = withSources(sources -> {
            sources.add(new Source("fake", SourceOrigin.FAKE, SourceStatus.FIXTURE, null, "x", null, null, null));
            return sources;
        });
        assertThat(blocks(review(fakeMarkedFixture))).containsExactly("SOURCE_FALSE_VERIFICATION");
    }

    @Test
    void modelHintsAloneNeverSupportACoreFact() {
        var research = withSources(sources -> {
            sources.add(new Source("hint", SourceOrigin.MODEL_HINT, SourceStatus.UNVERIFIED, "https://example.org/x",
                    "Hint", null, null, null));
            return sources;
        });
        var hinted = new Research(research.question(), research.angle(), research.investigations(),
                replace(new ArrayList<>(research.claims()), new Claim("c3", ClaimKind.FACT,
                        claim(base.claims(), "c3").text(), List.of(new EvidenceRef("hint", "abstract",
                                "would weigh about a billion tons", Relation.SUPPORTS)), null)),
                research.sources(), research.limitations(), research.scriptClaimIds());
        Validation validation = review(hinted);
        assertThat(blocks(validation)).containsExactly("CORE_EVIDENCE_INSUFFICIENT");
        assertThat(validation.findings().getFirst().location()).isEqualTo("research.claims[c3]");
    }

    @Test
    void contradictedOrInsufficientCoreFactsBlockTheScript() {
        var contradicted = withClaims(claims -> {
            Claim c2 = claim(claims, "c2");
            EvidenceRef ref = c2.evidenceRefs().getFirst();
            return replace(claims, new Claim("c2", ClaimKind.FACT, c2.text(), List.of(ref, new EvidenceRef(
                    ref.sourceId(), ref.locator(), ref.supportingExcerpt(), Relation.CONTRADICTS)), null));
        });
        assertThat(blocks(review(contradicted))).containsExactly("FACT_CONTRADICTED");

        var insufficient = withClaims(claims -> {
            Claim c2 = claim(claims, "c2");
            EvidenceRef ref = c2.evidenceRefs().getFirst();
            return replace(claims, new Claim("c2", ClaimKind.FACT, c2.text(), List.of(new EvidenceRef(
                    ref.sourceId(), ref.locator(), ref.supportingExcerpt(), Relation.INSUFFICIENT)), null));
        });
        assertThat(blocks(review(insufficient))).containsExactly("CORE_EVIDENCE_INSUFFICIENT");
    }

    @Test
    void hypothesesAndScenariosMustStateTheirUncertainty() {
        var research = withClaims(claims -> {
            Claim c5 = claim(claims, "c5");
            Claim c4 = claim(claims, "c4");
            replace(claims, new Claim("c5", ClaimKind.HYPOTHESIS, c5.text(), c5.evidenceRefs(), " "));
            return replace(claims, new Claim("c4", ClaimKind.SCENARIO, c4.text(), c4.evidenceRefs(), null));
        });
        Validation validation = review(research);
        assertThat(blocks(validation)).containsExactly("CLAIM_UNCERTAINTY_MISSING", "CLAIM_UNCERTAINTY_MISSING");
        assertThat(validation.findings().stream().map(f -> f.location()))
                .contains("research.claims[c4]", "research.claims[c5]");
    }

    @Test
    void unverifiedClaimsCannotBeUsedInTheScriptButMayStayOut() {
        var unverifiedCore = new Research(base.question(), base.angle(), base.investigations(),
                withClaims(claims -> {
                    claims.add(new Claim("u1", ClaimKind.UNVERIFIED, "Neutron stars hum at a fixed pitch.", List.of(),
                            null));
                    return claims;
                }).claims(), base.sources(), base.limitations(),
                List.of("c1", "c2", "c3", "c4", "c5", "u1"));
        assertThat(blocks(review(unverifiedCore))).containsExactly("CORE_UNVERIFIED_CLAIM");

        var unverifiedOptional = withClaims(claims -> {
            claims.add(new Claim("u1", ClaimKind.UNVERIFIED, "Neutron stars hum at a fixed pitch.", List.of(), null));
            return claims;
        });
        Validation optional = review(unverifiedOptional);
        assertThat(optional.blocked()).isFalse();
    }

    @Test
    void researchMustKeepTheSelectedQuestionAndAngle() {
        var changed = new Research("How hot is a neutron star?", base.angle(), base.investigations(), base.claims(),
                base.sources(), base.limitations(), base.scriptClaimIds());
        assertThat(blocks(review(changed))).containsExactly("RESEARCH_INPUT_MISMATCH");
    }

    @Test
    void missingPlannedInvestigationAndLimitationsAreRecommendations() {
        var research = new Research(base.question(), base.angle(), List.of(), base.claims(), base.sources(), List.of(),
                base.scriptClaimIds());
        Validation validation = review(research);
        assertThat(validation.blocked()).isFalse();
        assertThat(recommendations(validation)).contains("INVESTIGATION_PLAN_NOT_COVERED", "LIMITATIONS_MISSING");
    }

    @Test
    void malformedStructureIsBlockedNotThrown() {
        var duplicate = withClaims(claims -> {
            claims.add(claims.getFirst());
            return claims;
        });
        assertThat(blocks(review(duplicate))).contains("RESEARCH_STRUCTURE");
        var unknownSource = withClaims(claims -> {
            Claim c1 = claim(claims, "c1");
            return replace(claims, new Claim("c1", ClaimKind.FACT, c1.text(), List.of(new EvidenceRef("nope", "x",
                    "y", Relation.SUPPORTS)), null));
        });
        assertThat(blocks(review(unknownSource))).contains("EVIDENCE_SOURCE_UNKNOWN", "CORE_EVIDENCE_INSUFFICIENT");
        var nulls = new Research(null, null, null, null, null, null, null);
        assertThat(review(nulls).blocked()).isTrue();
        assertThat(review(null).blocked()).isTrue();
    }
}
