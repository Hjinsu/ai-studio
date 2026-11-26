package com.aistudio.production.contract;

/** Links a selected topic and its FAKE run in the transaction that completes SELECT. */
public interface ProductionSelectionPort {
    record Selection(String fingerprint, String title, String brief, int targetDurationSeconds, String language) {
        public Selection(String fingerprint, String title, String brief, int targetDurationSeconds) {
            this(fingerprint, title, brief, targetDurationSeconds, null);
        }
    }

    record Selected(long topicId, long contentId, long revisionId, long runId) {}

    Selected link(ProductionCostPort.StepOwner owner, Selection selection);
}
