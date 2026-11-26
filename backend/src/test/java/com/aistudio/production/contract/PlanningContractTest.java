package com.aistudio.production.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionPlanning.Finding;
import com.aistudio.production.contract.ProductionPlanning.ResultRef;
import com.aistudio.production.contract.ProductionPlanning.Severity;
import com.aistudio.production.contract.ProductionPlanning.Timing;
import com.aistudio.production.contract.ProductionPlanning.Validation;

class PlanningContractTest {
    @Test
    void timingBoundariesPreserveRecommendations() {
        assertThat(ProductionPlanning.timing(34_999)).isEqualTo(Timing.UNSUITABLE);
        assertThat(ProductionPlanning.timing(35_000)).isEqualTo(Timing.ADJUSTMENT_RECOMMENDED);
        assertThat(ProductionPlanning.timing(39_999)).isEqualTo(Timing.ADJUSTMENT_RECOMMENDED);
        assertThat(ProductionPlanning.timing(40_000)).isEqualTo(Timing.NORMAL);
        assertThat(ProductionPlanning.timing(60_000)).isEqualTo(Timing.NORMAL);
        assertThat(ProductionPlanning.timing(60_001)).isEqualTo(Timing.ADJUSTMENT_RECOMMENDED);
        assertThat(ProductionPlanning.timing(65_000)).isEqualTo(Timing.ADJUSTMENT_RECOMMENDED);
        assertThat(ProductionPlanning.timing(65_001)).isEqualTo(Timing.UNSUITABLE);
        assertThat(new Validation(List.of(new Finding(Severity.RECOMMENDATION, "HOOK", "s1",
                List.of(), "Sharpen the opening"))).blocked()).isFalse();
    }

    @Test
    void canonicalResultUsesUtf8AndBindsOperationAndStage() {
        var result = PlanningJson.body(Map.of("z", 1, "a", "조사"));
        assertThat(result.json()).isEqualTo("{\"a\":\"조사\",\"z\":1}");
        var ref = new ResultRef(15, ProductionStage.RESEARCH, result.hash());
        assertThat(PlanningJson.matches(ref, 15, ProductionStage.RESEARCH, result.json())).isTrue();
        assertThat(PlanningJson.matches(ref, 14, ProductionStage.RESEARCH, result.json())).isFalse();
        assertThat(PlanningJson.matches(ref, 15, ProductionStage.SCRIPT, result.json())).isFalse();
        assertThat(PlanningJson.matches(ref, 15, ProductionStage.RESEARCH, "{}")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PlanningJson.body(Map.of("text", "조".repeat(50_000))));
    }
}
