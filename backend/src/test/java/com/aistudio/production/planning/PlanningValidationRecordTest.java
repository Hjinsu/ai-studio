package com.aistudio.production.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.Finding;
import com.aistudio.production.contract.ProductionPlanning.Severity;
import com.aistudio.production.contract.ProductionPlanning.Validation;

import tools.jackson.databind.json.JsonMapper;

class PlanningValidationRecordTest {
    @Test
    void preservesTheLastBlockAndBoundsEscapedUtf8AcrossReplays() {
        String large = "🪐한\u0001".repeat(400);
        List<String> references = List.of(large, large, large, large, large);
        var recommendations = IntStream.range(0, 90)
                .mapToObj(i -> new Finding(Severity.RECOMMENDATION, "ADVICE_" + i, large, references, large));
        var block = new Finding(Severity.BLOCK, "REQUIRED_BLOCK", large, references, large);
        var validation = new Validation(Stream.concat(recommendations, Stream.of(block)).toList());

        String encoded = PlanningValidationRecord.encode(validation);
        Validation decoded = JsonMapper.builder().build().readValue(encoded, Validation.class);
        assertThat(encoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ProductionPlanning.MAX_BODY_BYTES);
        assertThat(PlanningValidationRecord.encode(validation)).isEqualTo(encoded);
        assertThat(decoded.blocked()).isTrue();
        assertThat(decoded.findings()).hasSizeLessThanOrEqualTo(65);
        assertThat(decoded.findings().getFirst().code()).isEqualTo("REQUIRED_BLOCK");
        assertThat(decoded.findings().getFirst().references()).hasSize(4);
        assertThat(decoded.findings().getFirst().location()).endsWith("…");
        assertThat(decoded.findings().getLast().correction()).contains("findings omitted", "0 required blocks");
    }
}
