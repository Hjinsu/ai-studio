package com.aistudio.production.stage;

import java.util.Optional;

/**
 * Documents whose text the server itself holds, looked up by document hash (SHA-256 of the text). The review only
 * accepts a source as evidence, and an excerpt as quoted, when the document is found here. V2-03 has only the
 * offline fixture library; documents retrieved by an approved adapter are added in V2-05.
 */
public interface TrustedDocuments {
    record Document(String documentHash, String url, String title, String publisher, String text, boolean fixture) {}

    Optional<Document> find(String documentHash);
}
