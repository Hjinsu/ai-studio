package com.aistudio.queue.pipeline;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aistudio.queue.pipeline.PipelineDefinition.Edge;
import com.aistudio.queue.pipeline.PipelineDefinition.Node;
import com.aistudio.queue.pipeline.PipelineDefinition.NodeKind;

/**
 * 등록된 불변 pipeline(A3 §7.1 T03 Fake pipeline, C 단계·비용 없음):
 * <pre>
 * research → script → storyboard → visual ──┐
 *                                └→ narration ─┴→ assemble → finalize
 * </pre>
 * 모든 노드는 TASK, requires_operation=false, 자동 3회(최초 포함)·수동 2회(FD-09/10). 생성 시점에 검증하고 실패하면
 * 빈 생성 단계에서 기동을 중단한다.
 */
@Component
public class PipelineRegistry {

    public static final String FAKE_SHORTS_V1 = "fake-shorts-v1";
    public static final String FAKE_LONGFORM_V1 = "fake-longform-v1";
    static final int DEFAULT_AUTO = 3;
    static final int DEFAULT_MANUAL = 2;

    private final Map<String, PipelineDefinition> byVersion;

    public PipelineRegistry() {
        this(List.of(fake(FAKE_SHORTS_V1, "SHORTS", 120), fake(FAKE_LONGFORM_V1, "LONGFORM", 600)));
    }

    PipelineRegistry(List<PipelineDefinition> definitions) {
        definitions.forEach(d -> PipelineValidator.validate(d, false));
        this.byVersion = definitions.stream()
                .collect(Collectors.toUnmodifiableMap(PipelineDefinition::version, Function.identity()));
        long selectors = definitions.stream().map(d -> d.executionMode() + "/" + d.contentType()).distinct().count();
        if (selectors != definitions.size()) {
            throw new IllegalStateException("each (executionMode, contentType) must select exactly one pipeline");
        }
    }

    /** 서버 선택: (executionMode, contentType) → 현재 버전. */
    public PipelineDefinition select(String executionMode, String contentType) {
        return byVersion.values().stream()
                .filter(d -> d.executionMode().equals(executionMode) && d.contentType().equals(contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no pipeline for " + executionMode + "/" + contentType));
    }

    public PipelineDefinition get(String version) {
        PipelineDefinition definition = byVersion.get(version);
        if (definition == null) {
            throw new IllegalArgumentException("unknown pipeline " + version);
        }
        return definition;
    }

    static PipelineDefinition fake(String version, String contentType, int timeoutSeconds) {
        List<String> keys = List.of("research", "script", "storyboard", "visual", "narration", "assemble",
                "finalize");
        List<Node> nodes = keys.stream()
                .map(k -> new Node(k, "FAKE_" + k.toUpperCase(java.util.Locale.ROOT), NodeKind.TASK, false,
                        DEFAULT_AUTO, DEFAULT_MANUAL, timeoutSeconds))
                .toList();
        List<Edge> edges = List.of(new Edge("research", "script"), new Edge("script", "storyboard"),
                new Edge("storyboard", "visual"), new Edge("storyboard", "narration"),
                new Edge("visual", "assemble"), new Edge("narration", "assemble"),
                new Edge("assemble", "finalize"));
        return new PipelineDefinition(version, "FAKE", contentType, nodes, edges);
    }
}
