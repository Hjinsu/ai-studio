package com.aistudio.production.stage.fake;

import java.util.Optional;

import com.aistudio.production.stage.TrustedDocuments;

/** The offline fixture documents, held by the server and always marked as fixtures. */
public final class FixtureDocuments implements TrustedDocuments {
    private final PlanningFixtures fixtures;

    public FixtureDocuments(PlanningFixtures fixtures) {
        this.fixtures = fixtures;
    }

    @Override
    public Optional<Document> find(String documentHash) {
        return fixtures.document(documentHash).map(document -> new Document(document.hash(), document.url(),
                document.title(), document.publisher(), document.text(), true));
    }
}
