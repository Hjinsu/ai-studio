package com.aistudio.production.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Claim;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;

/** Provider response structure and required values; strict JSON parsing and canonical hash. */
class DiscoveryResponseValidatorTest {
    private final Input input = DiscoveryFixtures.input(3, 5);
    private final Response valid = DiscoveryFixtures.response(5);

    private Response withFirst(UnaryOperator<Candidate> change) {
        List<Candidate> list = new ArrayList<>(valid.candidates());
        list.set(0, change.apply(list.get(0)));
        return new Response(valid.version(), list);
    }

    private static Candidate copy(Candidate c, String title, List<String> outline, Scores scores) {
        return new Candidate(c.coreTopic(), c.centralQuestion(), title, c.angle(), outline, c.evidencePlan(),
                c.assertions(), scores, c.rationale());
    }

    private void rejects(Response response, String field) {
        assertThatThrownBy(() -> DiscoveryResponseValidator.validate(input, response))
                .isInstanceOf(DiscoveryResponseException.class)
                .satisfies(e -> assertThat(((DiscoveryResponseException) e).field()).startsWith(field));
    }

    @Test
    void acceptsAWellFormedResponse() {
        assertThatCode(() -> DiscoveryResponseValidator.validate(input, valid)).doesNotThrowAnyException();
    }

    @Test
    void rejectsVersionAndCountMismatch() {
        rejects(new Response(2, valid.candidates()), "version");
        rejects(DiscoveryResponseFixtures.trim(valid, 4), "candidates");
        rejects(new Response(1, null), "candidates");
        rejects(null, "response");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", " padded", "line\nbreak", "tab\there", "\u0000" })
    void rejectsBlankPaddedAndControlText(String title) {
        rejects(withFirst(c -> copy(c, title, c.outline(), c.suggestedScores())), "candidates[0].title");
    }

    @Test
    void rejectsLengthsItemCountsAndScoreRanges() {
        rejects(withFirst(c -> copy(c, "x".repeat(DiscoveryRules.TITLE_MAX + 1), c.outline(), c.suggestedScores())),
                "candidates[0].title");
        rejects(withFirst(c -> copy(c, c.title(), List.of("only one"), c.suggestedScores())), "candidates[0].outline");
        rejects(withFirst(c -> copy(c, c.title(), List.of("1", "2", "3", "4", "5", "6"), c.suggestedScores())),
                "candidates[0].outline");
        rejects(withFirst(c -> copy(c, c.title(), c.outline(), new Scores(26, 0, 0, 0, 0, 0))),
                "candidates[0].suggestedScores.interest");
        rejects(withFirst(c -> copy(c, c.title(), c.outline(), new Scores(0, 0, 0, 0, 0, -1))),
                "candidates[0].suggestedScores.costEfficiency");
        rejects(withFirst(c -> copy(c, c.title(), c.outline(), null)), "candidates[0].suggestedScores");
        rejects(withFirst(c -> new Candidate(c.coreTopic(), c.centralQuestion(), c.title(), c.angle(), c.outline(),
                List.of(), c.assertions(), c.suggestedScores(), c.rationale())), "candidates[0].evidencePlan");
        rejects(withFirst(c -> new Candidate(c.coreTopic(), c.centralQuestion(), c.title(), c.angle(), c.outline(),
                List.of(new EvidencePlan("blog post", "q")), c.assertions(), c.suggestedScores(), c.rationale())),
                "candidates[0].evidencePlan[0].sourceType");
        rejects(withFirst(c -> new Candidate(c.coreTopic(), c.centralQuestion(), c.title(), c.angle(), c.outline(),
                c.evidencePlan(), List.of(new Claim("claim", "bad id with spaces")), c.suggestedScores(),
                c.rationale())), "candidates[0].assertions[0].evidenceId");
        rejects(withFirst(c -> new Candidate(null, c.centralQuestion(), c.title(), c.angle(), c.outline(),
                c.evidencePlan(), c.assertions(), c.suggestedScores(), c.rationale())), "candidates[0].coreTopic");
    }

    @Test
    void titleAtTheLimitIsAccepted() {
        Response response = withFirst(c -> copy(c, "T" + "x".repeat(DiscoveryRules.TITLE_MAX - 1), c.outline(),
                c.suggestedScores()));
        assertThatCode(() -> DiscoveryResponseValidator.validate(input, response)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ codec

    @Test
    void canonicalJsonIsStableAndRoundTrips() {
        String json = DiscoveryResponseCodec.canonicalJson(valid);
        assertThat(json).startsWith("{\"candidates\":[{\"angle\":").doesNotContain(" \"").contains("\"version\":1}");
        assertThat(DiscoveryResponseCodec.parse(json)).isEqualTo(valid);
        assertThat(DiscoveryResponseCodec.sha256(DiscoveryResponseCodec.parse(json)))
                .isEqualTo(DiscoveryResponseCodec.sha256(valid)).matches("[0-9a-f]{64}");
    }

    @Test
    void parserRejectsUnknownDuplicateMissingAndMistypedFields() {
        String json = DiscoveryResponseCodec.canonicalJson(valid);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(json.replace("{\"candidates\"",
                "{\"verified\":true,\"candidates\""))).isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(json.replace("\"version\":1}",
                "\"version\":1,\"version\":1}"))).isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(json.replace("\"version\":1}", "\"version\":1.5}")))
                .isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(json.replace("\"version\":1}", "\"version\":\"1\"}")))
                .isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(json.replaceFirst("\"rationale\":\"[^\"]*\",", "")))
                .isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse("[]")).isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse("not json"))
                .isInstanceOf(DiscoveryResponseException.class);
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse("x".repeat(DiscoveryRules.MAX_RESPONSE_BYTES + 1)))
                .isInstanceOf(DiscoveryResponseException.class);
    }

    @Test
    void parserRejectsDeepNestingAndKeepsNullEvidenceIds() {
        assertThatThrownBy(() -> DiscoveryResponseCodec.parse(
                "{\"version\":1,\"candidates\":[[[[[[[1]]]]]]]}")).isInstanceOf(DiscoveryResponseException.class);
        Response claim = withFirst(c -> new Candidate(c.coreTopic(), c.centralQuestion(), c.title(), c.angle(),
                c.outline(), c.evidencePlan(), List.of(new Claim("A claim", null)), c.suggestedScores(),
                c.rationale()));
        assertThat(DiscoveryResponseCodec.parse(DiscoveryResponseCodec.canonicalJson(claim))).isEqualTo(claim);
    }

    /** Small helpers kept local to the test. */
    static final class DiscoveryResponseFixtures {
        static Response trim(Response response, int count) {
            return new Response(response.version(), response.candidates().subList(0, count));
        }
    }
}
