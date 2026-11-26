package com.aistudio.production.contract;

import java.util.List;

import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;

/** Deterministic evaluation over the channel snapshot and a bounded catalog snapshot. */
public interface DiscoveryEvaluator {
    record CatalogEntry(String topicId, String contentId, String revisionId, String normalizedTopicHash,
            String title, String brief) {}

    List<EvaluatedCandidate> evaluate(Input input, Response response, List<CatalogEntry> catalog);
}
