package com.aistudio.production.contract;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.aistudio.content.command.CanonicalJson;

/** Manual file exchange identity. File integrity is separate from approval to adopt a result. */
public final class WorkerExchange {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<ProductionStage> ELIGIBLE_STAGES = Set.of(ProductionStage.DISCOVER,
            ProductionStage.RESEARCH, ProductionStage.SCRIPT, ProductionStage.SCENES);

    private WorkerExchange() {}

    public record Identity(int schemaVersion, long jobId, long requestId, long stepId, ProductionStage stage,
            int attempt, long epoch, String inputSha256, String selectionSha256) {
        public Identity {
            requireHash(inputSha256);
            if (schemaVersion != 1 || jobId < 1 || requestId < 1 || stepId < 1 || attempt < 1 || epoch < 1
                    || !ELIGIBLE_STAGES.contains(Objects.requireNonNull(stage))) {
                throw new IllegalArgumentException("Invalid worker exchange identity");
            }
            if (stage == ProductionStage.DISCOVER) {
                if (selectionSha256 != null) {
                    throw new IllegalArgumentException("Discovery precedes selection");
                }
            } else {
                requireHash(selectionSha256);
            }
        }
    }

    public record Request(Identity identity, String canonicalInput) {
        public Request {
            if (!Objects.requireNonNull(identity).inputSha256().equals(CanonicalJson.sha256Hex(canonicalInput))) {
                throw new IllegalArgumentException("Worker input hash mismatch");
            }
        }
    }

    public record Draft(Identity identity, String payload, String payloadSha256) {
        public Draft {
            Objects.requireNonNull(identity);
            requirePayload(payload, payloadSha256);
        }
    }

    public record Review(Identity identity, String draftSha256, String payload, String payloadSha256) {
        public Review {
            Objects.requireNonNull(identity);
            requireHash(draftSha256);
            requirePayload(payload, payloadSha256);
        }
    }

    /** The caller supplies the active database identity, including the current selection fingerprint. */
    public static void verifyLineage(Identity active, Draft draft, Review review) {
        if (!Objects.requireNonNull(active).equals(draft.identity()) || !active.equals(review.identity())
                || !draft.payloadSha256().equals(review.draftSha256())) {
            throw new IllegalArgumentException("Stale worker result or unbound review");
        }
    }

    private static void requirePayload(String payload, String hash) {
        requireHash(hash);
        if (!CanonicalJson.sha256Hex(payload).equals(hash)) {
            throw new IllegalArgumentException("Worker payload hash mismatch");
        }
    }

    private static void requireHash(String hash) {
        if (hash == null || !SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException("Expected lowercase SHA-256");
        }
    }
}
