package com.aistudio.queue.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.aistudio.queue.pipeline.PipelineDefinition.Edge;
import com.aistudio.queue.pipeline.PipelineDefinition.Node;
import com.aistudio.queue.pipeline.PipelineDefinition.NodeKind;

/**
 * Pipeline 정적 검증(A3 §7.1~7.3). 기동 시 모든 등록 pipeline을 검증하고 실패하면 기동을 중단한다.
 * 순환 없음(위상 정렬), job_key 유일, 노드 ≤ 64·간선 ≤ 256, 중복·자기 간선 금지, 정책 범위(V7 CHECK와 동일),
 * EXPANDER는 정적 간선으로 BARRIER를 직접 후행으로 갖고 BARRIER는 EXPANDER를 직접 선행으로 가진다.
 * T03-C는 외부 효과가 없는 FAKE 전용이므로 requires_operation=true 노드를 거부한다(T03-D 이후 별도 버전).
 */
public final class PipelineValidator {

    public static final int MAX_NODES = 64;
    public static final int MAX_EDGES = 256;
    private static final Pattern JOB_KEY = Pattern.compile("^[a-z0-9][a-z0-9:/._-]{0,127}$");
    private static final Pattern JOB_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,47}$");
    private static final Pattern VERSION = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private PipelineValidator() {
    }

    public static final class InvalidPipelineException extends IllegalStateException {
        InvalidPipelineException(String version, String message) {
            super("pipeline " + version + ": " + message);
        }
    }

    /** 검증에 성공하면 위상 정렬된 job_key 목록을 반환한다. */
    public static List<String> validate(PipelineDefinition p, boolean allowOperations) {
        String v = p.version();
        if (v == null || !VERSION.matcher(v).matches()) {
            throw new InvalidPipelineException(v, "invalid version");
        }
        if (!"FAKE".equals(p.executionMode())) {
            throw new InvalidPipelineException(v, "only FAKE execution mode is allowed in T03 (FD-13)");
        }
        if (!"SHORTS".equals(p.contentType()) && !"LONGFORM".equals(p.contentType())) {
            throw new InvalidPipelineException(v, "invalid content type");
        }
        if (p.nodes().isEmpty() || p.nodes().size() > MAX_NODES) {
            throw new InvalidPipelineException(v, "node count must be 1.." + MAX_NODES);
        }
        if (p.edges().size() > MAX_EDGES) {
            throw new InvalidPipelineException(v, "edge count must be <= " + MAX_EDGES);
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Node n : p.nodes()) {
            if (n.jobKey() == null || !JOB_KEY.matcher(n.jobKey()).matches()) {
                throw new InvalidPipelineException(v, "invalid job_key " + n.jobKey());
            }
            if (n.jobType() == null || !JOB_TYPE.matcher(n.jobType()).matches()) {
                throw new InvalidPipelineException(v, "invalid job_type " + n.jobType());
            }
            if (nodes.put(n.jobKey(), n) != null) {
                throw new InvalidPipelineException(v, "duplicate job_key " + n.jobKey());
            }
            if (n.maxAutoAttempts() < 1 || n.maxAutoAttempts() > 10 || n.maxManualResumes() < 0
                    || n.maxManualResumes() > 10 || n.executionTimeoutSeconds() < 1
                    || n.executionTimeoutSeconds() > 86400) {
                throw new InvalidPipelineException(v, "policy out of range for " + n.jobKey());
            }
            if (n.requiresOperation() && !allowOperations) {
                throw new InvalidPipelineException(v, "requires_operation is not allowed before T03-D: " + n.jobKey());
            }
        }
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, List<String>> predecessors = new HashMap<>();
        nodes.keySet().forEach(k -> {
            successors.put(k, new ArrayList<>());
            predecessors.put(k, new ArrayList<>());
        });
        Set<Edge> seen = new HashSet<>();
        for (Edge e : p.edges()) {
            if (!nodes.containsKey(e.predecessor()) || !nodes.containsKey(e.successor())) {
                throw new InvalidPipelineException(v, "edge references unknown job " + e);
            }
            if (e.predecessor().equals(e.successor())) {
                throw new InvalidPipelineException(v, "self edge " + e.predecessor());
            }
            if (!seen.add(e)) {
                throw new InvalidPipelineException(v, "duplicate edge " + e);
            }
            successors.get(e.predecessor()).add(e.successor());
            predecessors.get(e.successor()).add(e.predecessor());
        }
        for (Node n : nodes.values()) {
            if (n.kind() == NodeKind.BARRIER && predecessors.get(n.jobKey()).stream()
                    .noneMatch(k -> nodes.get(k).kind() == NodeKind.EXPANDER)) {
                throw new InvalidPipelineException(v, "BARRIER must have an EXPANDER as a direct predecessor: "
                        + n.jobKey());
            }
            if (n.kind() == NodeKind.EXPANDER && successors.get(n.jobKey()).stream()
                    .noneMatch(k -> nodes.get(k).kind() == NodeKind.BARRIER)) {
                throw new InvalidPipelineException(v, "EXPANDER must have a static edge to a BARRIER: " + n.jobKey());
            }
        }
        // Kahn 위상 정렬(입력 순서를 안정적으로 유지)
        Map<String, Integer> indegree = new HashMap<>();
        predecessors.forEach((k, list) -> indegree.put(k, list.size()));
        Deque<String> ready = new ArrayDeque<>();
        for (String k : nodes.keySet()) {
            if (indegree.get(k) == 0) {
                ready.add(k);
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String k = ready.poll();
            order.add(k);
            for (String s : successors.get(k)) {
                if (indegree.merge(s, -1, Integer::sum) == 0) {
                    ready.add(s);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new InvalidPipelineException(v, "cycle detected");
        }
        return List.copyOf(order);
    }
}
