package com.aistudio.production.stage.fake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aistudio.production.contract.PlanningProvider;
import com.aistudio.production.contract.ResearchEvidenceProvider;
import com.aistudio.production.stage.PlanningReview;
import com.aistudio.production.stage.TrustedDocuments;

/**
 * V2-03 wires only FAKE planning: the provider and evidence lookup work on the offline fixture library, and the
 * review trusts only those fixture documents. The provider is called solely by the planning adapter inside the
 * cost boundary; whether new requests use the planning pipeline at all is a separate, default-off setting
 * ({@code ai-studio.production.planning-enabled}).
 */
@Configuration(proxyBeanMethods = false)
class FakePlanningConfiguration {
    @Bean
    PlanningFixtures planningFixtures() {
        return PlanningFixtures.bundled();
    }

    @Bean
    TrustedDocuments trustedDocuments(PlanningFixtures fixtures) {
        return new FixtureDocuments(fixtures);
    }

    @Bean
    ResearchEvidenceProvider researchEvidenceProvider(PlanningFixtures fixtures) {
        return new FakeResearchEvidenceProvider(fixtures);
    }

    @Bean
    PlanningProvider planningProvider(PlanningFixtures fixtures, ResearchEvidenceProvider evidence) {
        return new FakePlanningProvider(fixtures, evidence);
    }

    @Bean
    PlanningReview planningReview(TrustedDocuments documents) {
        return new PlanningReview(documents);
    }
}
