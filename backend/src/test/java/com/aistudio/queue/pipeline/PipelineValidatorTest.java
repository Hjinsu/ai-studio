package com.aistudio.queue.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aistudio.queue.pipeline.PipelineDefinition.Edge;
import com.aistudio.queue.pipeline.PipelineDefinition.Node;
import com.aistudio.queue.pipeline.PipelineDefinition.NodeKind;

/** 기동 시 pipeline 검증(A3 §7.1~7.3, Q-01). */
class PipelineValidatorTest {

    private static Node task(String key) {
        return new Node(key, "T_" + key.toUpperCase(), NodeKind.TASK, false, 3, 2, 60);
    }

    private static Node node(String key, NodeKind kind) {
        return new Node(key, "T_" + key.toUpperCase(), kind, false, 3, 2, 60);
    }

    private static PipelineDefinition def(List<Node> nodes, List<Edge> edges) {
        return new PipelineDefinition("test-v1", "FAKE", "SHORTS", nodes, edges);
    }

    private static void rejected(PipelineDefinition p, String message) {
        assertThatThrownBy(() -> PipelineValidator.validate(p, false)).hasMessageContaining(message);
    }

    @Test
    void registeredFakePipelinesAreValidAndOrdered() {
        PipelineRegistry registry = new PipelineRegistry();
        PipelineDefinition shorts = registry.select("FAKE", "SHORTS");
        assertThat(shorts.version()).isEqualTo(PipelineRegistry.FAKE_SHORTS_V1);
        assertThat(registry.select("FAKE", "LONGFORM").version()).isEqualTo(PipelineRegistry.FAKE_LONGFORM_V1);
        List<String> order = PipelineValidator.validate(shorts, false);
        assertThat(order).startsWith("research", "script", "storyboard").endsWith("assemble", "finalize");
        assertThat(order.indexOf("visual")).isLessThan(order.indexOf("assemble"));
        assertThat(order.indexOf("narration")).isLessThan(order.indexOf("assemble"));
        assertThat(shorts.nodes()).allSatisfy(n -> {
            assertThat(n.requiresOperation()).isFalse();
            assertThat(n.maxAutoAttempts()).isEqualTo(3);
            assertThat(n.maxManualResumes()).isEqualTo(2);
        });
        assertThatThrownBy(() -> registry.select("LIVE", "SHORTS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cyclesSelfEdgesAndDuplicatesAreRejected() {
        rejected(def(List.of(task("a"), task("b"), task("c")),
                List.of(new Edge("a", "b"), new Edge("b", "c"), new Edge("c", "a"))), "cycle");
        rejected(def(List.of(task("a")), List.of(new Edge("a", "a"))), "self edge");
        rejected(def(List.of(task("a"), task("b")), List.of(new Edge("a", "b"), new Edge("a", "b"))), "duplicate edge");
        rejected(def(List.of(task("a"), task("a")), List.of()), "duplicate job_key");
        rejected(def(List.of(task("a")), List.of(new Edge("a", "zz"))), "unknown job");
    }

    @Test
    void limitsPolicyAndModeAreEnforced() {
        List<Node> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(task("n" + i));
        }
        rejected(def(many, List.of()), "node count");
        rejected(def(List.of(new Node("a", "T_A", NodeKind.TASK, false, 0, 2, 60)), List.of()), "policy");
        rejected(def(List.of(new Node("a", "T_A", NodeKind.TASK, false, 3, 11, 60)), List.of()), "policy");
        rejected(def(List.of(new Node("a", "T_A", NodeKind.TASK, false, 3, 2, 86401)), List.of()), "policy");
        rejected(def(List.of(new Node("a", "T_A", NodeKind.TASK, true, 3, 2, 60)), List.of()), "requires_operation");
        rejected(def(List.of(new Node("A", "T_A", NodeKind.TASK, false, 3, 2, 60)), List.of()), "invalid job_key");
        rejected(new PipelineDefinition("x-v1", "LIVE", "SHORTS", List.of(task("a")), List.of()), "FAKE");
    }

    @Test
    void expanderAndBarrierContract() {
        rejected(def(List.of(node("b", NodeKind.BARRIER), task("a")), List.of(new Edge("a", "b"))),
                "BARRIER must have an EXPANDER");
        rejected(def(List.of(node("e", NodeKind.EXPANDER), task("a")), List.of(new Edge("e", "a"))),
                "EXPANDER must have a static edge to a BARRIER");
        assertThat(PipelineValidator.validate(def(List.of(task("r"), node("e", NodeKind.EXPANDER),
                node("b", NodeKind.BARRIER), task("f")), List.of(new Edge("r", "e"), new Edge("e", "b"),
                        new Edge("b", "f"))), false)).containsExactly("r", "e", "b", "f");
    }

    @Test
    void registryRejectsAmbiguousSelection() {
        PipelineDefinition a = PipelineRegistry.fake("fake-shorts-a", "SHORTS", 60);
        PipelineDefinition b = PipelineRegistry.fake("fake-shorts-b", "SHORTS", 60);
        assertThatThrownBy(() -> new PipelineRegistry(List.of(a, b))).hasMessageContaining("exactly one pipeline");
    }
}
