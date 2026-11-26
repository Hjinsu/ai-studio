package com.aistudio.production.planning;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.stage.PlanningReview;

@Component
public class PlanningAvailability {
    private final boolean enabled;
    private final ObjectProvider<DiscoveryExecutionPort> discovery;
    private final ObjectProvider<PlanningExecutionPort> execution;
    private final ObjectProvider<PlanningProvider> provider;
    private final ObjectProvider<PlanningReview> review;

    public PlanningAvailability(@Value("${ai-studio.production.planning-enabled:false}") boolean enabled,
            ObjectProvider<DiscoveryExecutionPort> discovery, ObjectProvider<PlanningExecutionPort> execution,
            ObjectProvider<PlanningProvider> provider, ObjectProvider<PlanningReview> review) {
        this.enabled = enabled;
        this.discovery = discovery;
        this.execution = execution;
        this.provider = provider;
        this.review = review;
    }

    public boolean ready() {
        return enabled && discovery.getIfAvailable() != null && execution.getIfAvailable() != null
                && provider.getIfAvailable() != null && review.getIfAvailable() != null;
    }
}
