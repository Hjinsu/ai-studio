package com.aistudio.production.execution;

import java.util.Objects;

import com.aistudio.production.contract.DiscoveryEvaluator;
import com.aistudio.production.contract.DiscoveryExecutionPort;

/**
 * V2-02 collaborators of the executor for {@code v2-discovery-1} requests. Without this flow the executor claims
 * only {@code v2-fixed-1} requests, as before.
 */
public record DiscoveryFlow(DiscoveryExecutionPort port, DiscoveryEvaluator evaluator) {
    public DiscoveryFlow {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(evaluator, "evaluator");
    }
}
