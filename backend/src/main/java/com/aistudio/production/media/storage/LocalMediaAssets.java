package com.aistudio.production.media.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aistudio.production.contract.MediaAssets;
import com.aistudio.production.contract.MediaEffectPort.Payload;
import com.aistudio.production.contract.MediaStageWork.Context;
import com.aistudio.production.contract.MediaStore.Attempt;
import com.aistudio.production.contract.MediaStore.FileIdentity;
import com.aistudio.production.contract.MediaStore.Role;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.json.JsonMapper;

@Component
public class LocalMediaAssets implements MediaAssets {
    private final LocalAssetMapper mapper;
    private final LockRetryingTransactions transactions;
    private final JsonMapper json;
    private final String root;

    public LocalMediaAssets(LocalAssetMapper mapper, LockRetryingTransactions transactions, JsonMapper json,
            @Value("${ai-studio.production.media.storage-root:}") String root) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.json = json;
        this.root = root;
    }

    @Override
    public void publish(Context context, java.util.List<Payload> payloads) throws IOException {
        Set<Role> required = switch (context.stage()) {
            case SUBTITLE -> Set.of(Role.ASS, Role.SRT);
            case RENDER -> Set.of(Role.MP4);
            case METADATA -> Set.of(Role.MEDIA_METADATA);
            case QC -> Set.of(Role.QC_REPORT, Role.MANIFEST);
            default -> throw new IllegalArgumentException("Not a local media step");
        };
        if (context.items().size() != 1 || payloads.size() != required.size()
                || !required.equals(payloads.stream().map(Payload::role).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("Incomplete local media bundle");
        }
        long itemId = context.items().getFirst().id();
        int number = context.attemptNo();
        long epoch = context.owner().leaseEpoch();
        if ("APPLIED".equals(context.items().getFirst().state())) {
            return;
        }
        LocalAssetMapper.Lease lease = transactions.execute(() -> {
            var current = requireLease(context);
            if (context.recoveryOnly() || mapper.begin(itemId, context.owner().requestId(), context.owner().stepId(),
                    number, epoch) != 1) {
                throw new IllegalStateException("Local media attempt is already used");
            }
            mapper.abandonPrevious(itemId, number);
            return current;
        });
        var store = store();
        var attempt = new Attempt(lease.channelId(), lease.contentId(), lease.runId(), itemId, number);
        var identities = new ArrayList<FileIdentity>(payloads.size());
        for (var payload : payloads) {
            if (context.cancelled().getAsBoolean()) {
                throw new IOException("Local media stage cancelled");
            }
            MediaMetadata.require(payload, json);
            try (var input = payload.content().open()) {
                identities.add(store.stage(attempt, payload.role(), input, payload.byteLimit()));
            }
        }
        transactions.run(() -> {
            requireLease(context);
            for (int i = 0; i < payloads.size(); i++) {
                var payload = payloads.get(i);
                var identity = identities.get(i);
                mapper.stage(itemId, number, payload.role(), epoch, attempt.relativePath(payload.role()),
                        payload.mimeType(), identity.bytes(), identity.sha256(), payload.lineageJson(), payload.rightsJson());
            }
        });
        for (int i = 0; i < payloads.size(); i++) {
            store.commit(attempt, payloads.get(i).role(), identities.get(i));
        }
        transactions.run(() -> {
            requireLease(context);
            if (context.stage() != ProductionStage.RENDER && context.stage() != ProductionStage.QC
                    && mapper.finalizeAssets(itemId, number) != payloads.size()) {
                throw new IllegalStateException("Local media bundle changed");
            }
            if (mapper.apply(itemId, number, epoch) != 1) {
                throw new IllegalStateException("Local media attempt changed");
            }
        });
    }

    @Override
    public java.util.List<Stored> inputs(Context context, ProductionStage stage, Role role) {
        return transactions.execute(() -> {
            requireLease(context);
            return mapper.inputs(context.owner().requestId(), stage.name(), role, context.stage() == ProductionStage.QC)
                    .stream().map(LocalMediaAssets::stored).toList();
        });
    }

    @Override
    public FileChannel open(Context context, Stored asset) throws IOException {
        OpenAsset input = transactions.execute(() -> new OpenAsset(requireLease(context),
                mapper.find(context.owner().requestId(), asset.id(), context.stage() == ProductionStage.QC)));
        if (input.asset() == null || !stored(input.asset()).equals(asset)) {
            throw new IOException("Media input changed");
        }
        var lease = input.lease();
        var attempt = new Attempt(lease.channelId(), lease.contentId(), lease.runId(), input.asset().itemId(),
                input.asset().attemptNo());
        if (!attempt.relativePath(asset.role()).equals(input.asset().relativePath())) {
            throw new IOException("Media input path mismatch");
        }
        return store().openVerified(attempt, asset.role(), asset.identity());
    }

    /** Recheck files after the product worker returns, including when QC resumes after a JVM exit. */
    public void verifyQc(Context context) throws IOException {
        for (Role role : Set.of(Role.MP4, Role.QC_REPORT, Role.MANIFEST)) {
            var candidates = inputs(context, role == Role.MP4 ? ProductionStage.RENDER : ProductionStage.QC, role);
            if (candidates.size() != 1) {
                throw new IOException("QC asset bundle is incomplete");
            }
            try (var input = open(context, candidates.getFirst())) {
                // Opening the file verifies length and content hash.
            }
        }
    }

    /** Called inside the request-fenced QC completion transaction. */
    public boolean finalizeQc(long requestId, long itemId, int attempt) {
        if (mapper.finalizeVideo(requestId) != 1 || mapper.finalizeAssets(itemId, attempt) != 2) {
            throw new IllegalStateException("QC did not produce a complete media bundle");
        }
        return true;
    }

    private LocalAssetMapper.Lease requireLease(Context context) {
        var owner = context.owner();
        var lease = mapper.lock(owner.requestId(), owner.stepId(), owner.leaseOwner(), owner.leaseEpoch());
        if (lease == null || !lease.current() || lease.cancelled() || lease.stepAttempts() != context.attemptNo()) {
            throw new IllegalStateException("Local media lease is not active");
        }
        return lease;
    }

    private record OpenAsset(LocalAssetMapper.Lease lease, LocalAssetMapper.Asset asset) {}

    private WorkspaceMediaStore store() throws IOException {
        if (root.isBlank()) {
            throw new IOException("Media storage is not configured");
        }
        return new WorkspaceMediaStore(Path.of(root));
    }

    private static Stored stored(LocalAssetMapper.Asset asset) {
        return new Stored(asset.id(), asset.role(), asset.mimeType(), new FileIdentity(asset.byteLength(), asset.sha256()));
    }
}
