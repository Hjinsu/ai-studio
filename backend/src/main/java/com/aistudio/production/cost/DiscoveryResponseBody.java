package com.aistudio.production.cost;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.DiscoveryProvider.InvalidResponse;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.topic.TopicNormalizer;

import tools.jackson.databind.json.JsonMapper;

final class DiscoveryResponseBody {
    private DiscoveryResponseBody() {}

    static String canonical(JsonMapper json, Object value) {
        return CanonicalJson.write(json.convertValue(value, Object.class));
    }

    static String validate(JsonMapper json, Input input, Response response) {
        require(response != null && response.version() == input.rulesVersion());
        items(response.candidates(), input.candidateCount(), input.candidateCount(), DiscoveryResponseBody::candidate);
        try {
            String body = canonical(json, response);
            require(body.getBytes(StandardCharsets.UTF_8).length <= 32768);
            return body;
        } catch (IllegalArgumentException e) {
            throw new InvalidResponse();
        }
    }

    private static void candidate(Candidate candidate) {
        text(candidate.coreTopic(), 255);
        text(candidate.centralQuestion(), 500);
        text(candidate.title(), 255);
        String title = TopicNormalizer.normalize(candidate.title());
        require(title.codePointCount(0, title.length()) <= 255);
        text(candidate.angle(), 500);
        text(candidate.rationale(), 1000);
        items(candidate.outline(), 2, 5, line -> text(line, 500));
        items(candidate.evidencePlan(), 1, 5, plan -> {
            text(plan.sourceType(), 120);
            text(plan.searchQuestion(), 500);
        });
        items(candidate.assertions(), 0, 10, claim -> {
            text(claim.assertion(), 500);
            if (claim.evidenceId() != null) {
                text(claim.evidenceId(), 128);
            }
        });
        Scores scores = candidate.suggestedScores();
        require(scores != null);
        score(scores.interest(), 25);
        score(scores.channelFit(), 20);
        score(scores.originality(), 20);
        score(scores.visualPotential(), 15);
        score(scores.evidencePotential(), 15);
        score(scores.costEfficiency(), 5);
    }

    private static <T> void items(List<T> values, int min, int max, Consumer<T> validate) {
        require(values != null && values.size() >= min && values.size() <= max);
        for (T value : values) {
            require(value != null);
            validate.accept(value);
        }
    }

    private static void text(String value, int max) {
        require(value != null && value.codePointCount(0, value.length()) <= max
                && value.equals(value.strip()) && !TopicNormalizer.normalize(value).isBlank()
                && value.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.FORMAT));
    }

    private static void score(int value, int max) {
        require(value >= 0 && value <= max);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new InvalidResponse();
        }
    }
}
