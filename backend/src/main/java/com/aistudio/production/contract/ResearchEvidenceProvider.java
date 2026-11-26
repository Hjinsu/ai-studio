package com.aistudio.production.contract;

import java.util.List;

import com.aistudio.production.contract.ProductionPlanning.Claim;
import com.aistudio.production.contract.ProductionPlanning.EvidenceRef;
import com.aistudio.production.contract.ProductionPlanning.Source;

/** Trusted document lookup and claim assessment are separate from generation. */
public interface ResearchEvidenceProvider {
    record Document(Source source, String text) {}

    List<Document> retrieve(String question, List<String> queries);

    List<EvidenceRef> verify(Claim claim, List<Document> retrievedDocuments);
}
