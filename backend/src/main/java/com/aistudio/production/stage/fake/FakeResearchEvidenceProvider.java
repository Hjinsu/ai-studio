package com.aistudio.production.stage.fake;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.EvidenceRef;
import com.aistudio.production.contract.ProductionPlanning.Relation;
import com.aistudio.production.contract.ProductionPlanning.Source;
import com.aistudio.production.contract.ProductionPlanning.SourceOrigin;
import com.aistudio.production.contract.ProductionPlanning.SourceStatus;
import com.aistudio.production.contract.ResearchEvidenceProvider;

/**
 * FAKE document lookup and claim assessment over the offline fixture library. It never makes a network call.
 * Documents come back as {@code origin=FIXTURE, status=FIXTURE} without a lookup time, because nothing was looked
 * up. An assessment is {@code SUPPORTS} or {@code CONTRADICTS} only when the fixture excerpt is found in the
 * document text; otherwise it is {@code INSUFFICIENT}.
 */
public final class FakeResearchEvidenceProvider implements ResearchEvidenceProvider {
    private final PlanningFixtures fixtures;

    public FakeResearchEvidenceProvider(PlanningFixtures fixtures) {
        this.fixtures = fixtures;
    }

    @Override
    public List<Document> retrieve(String question, List<String> queries) {
        return fixtures.forQuestion(question).map(topic -> topic.documents().stream()
                .map(document -> new Document(new Source(PlanningFixtures.sourceId(topic, document),
                        SourceOrigin.FIXTURE, SourceStatus.FIXTURE, document.url(), document.title(),
                        document.publisher(), null, document.hash()), document.text()))
                .toList()).orElse(List.of());
    }

    @Override
    public List<EvidenceRef> verify(Claim claim, List<Document> retrievedDocuments) {
        List<EvidenceRef> result = new ArrayList<>();
        for (PlanningFixtures.Topic topic : fixtures.topics()) {
            Optional<PlanningFixtures.Claim> spec = topic.claims().stream()
                    .filter(item -> item.text().equals(claim.text())).findFirst();
            if (spec.isEmpty()) {
                continue;
            }
            for (PlanningFixtures.Evidence evidence : spec.get().evidence()) {
                String sourceId = topic.key() + ":" + evidence.document();
                retrievedDocuments.stream().filter(document -> document.source().id().equals(sourceId)).findFirst()
                        .ifPresent(document -> {
                            boolean found = document.text().contains(evidence.excerpt());
                            Relation relation = found ? evidence.relation() : Relation.INSUFFICIENT;
                            result.add(new EvidenceRef(sourceId, evidence.locator(), evidence.excerpt(), relation));
                        });
            }
            break;
        }
        return result;
    }
}
