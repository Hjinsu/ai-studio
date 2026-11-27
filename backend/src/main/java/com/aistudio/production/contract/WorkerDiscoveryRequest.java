package com.aistudio.production.contract;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.aistudio.production.contract.ProductionDiscovery.Input;

/** Versioned editorial snapshot for DISCOVER. Candidate count and production target are separate. */
public record WorkerDiscoveryRequest(Input discovery, long channelId, String concept, String limitKrw,
        int targetProductions, List<RecentTopic> recent, List<String> evaluationCriteria) {
    public record RecentTopic(String topicId, String title, String centralQuestion) {}

    public WorkerDiscoveryRequest {
        Objects.requireNonNull(discovery);
        recent = List.copyOf(recent);
        evaluationCriteria = List.copyOf(evaluationCriteria);
        BigDecimal limit = new BigDecimal(limitKrw);
        if (channelId < 1 || concept == null || concept.isBlank() || targetProductions < 1
                || limit.compareTo(ProductionMediaBudget.requestLimit(limit)) != 0
                || discovery.candidateCount() < ProductionDiscovery.MIN_CANDIDATES
                || discovery.candidateCount() > ProductionDiscovery.MAX_CANDIDATES) {
            throw new IllegalArgumentException("Invalid discovery snapshot");
        }
    }
}
