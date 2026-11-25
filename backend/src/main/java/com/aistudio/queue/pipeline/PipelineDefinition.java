package com.aistudio.queue.pipeline;

import java.util.List;

/**
 * 코드에 정의된 불변 pipeline 버전(A3 §7.1). 배포한 버전의 노드·간선·job별 정책은 바꾸지 않는다(새 버전으로만 변경).
 * 서버가 (executionMode, contentType)으로 현재 버전을 고르며 클라이언트는 고르지 않는다.
 */
public record PipelineDefinition(String version, String executionMode, String contentType, List<Node> nodes,
        List<Edge> edges) {

    public enum NodeKind {
        TASK, EXPANDER, BARRIER
    }

    /** job 정책은 job 행에 복사되어 불변으로 저장된다. */
    public record Node(String jobKey, String jobType, NodeKind kind, boolean requiresOperation, int maxAutoAttempts,
            int maxManualResumes, int executionTimeoutSeconds) {
    }

    /** predecessor → successor. */
    public record Edge(String predecessor, String successor) {
    }

    public PipelineDefinition {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
