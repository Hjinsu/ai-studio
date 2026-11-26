package com.aistudio.production.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.production.contract.ProductionDiscovery;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.PlanningJson;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.planning.PlanningResultMapper;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper.Batch;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.PageResponse;
import com.aistudio.shared.api.PageResponse.PageQuery;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.json.JsonMapper;

@Service
class ProductionRequestService {
    record Created(ProductionRequestMapper.Request request, boolean replayed) {}

    private final ProductionRequestMapper mapper;
    private final ChannelService channels;
    private final ProductionDiscoveryMapper discovery;
    private final JsonMapper json;
    private final BudgetControlLock control;
    private final LockRetryingTransactions transactions;
    private final PlanningResultMapper planningResults;
    private final String shortsPipeline;

    ProductionRequestService(ProductionRequestMapper mapper, ChannelService channels,
            BudgetControlLock control, LockRetryingTransactions transactions, ProductionDiscoveryMapper discovery,
            JsonMapper json, PlanningResultMapper planningResults,
            @Value("${ai-studio.production.planning-enabled:false}") boolean planningEnabled) {
        this.mapper = mapper;
        this.channels = channels;
        this.control = control;
        this.transactions = transactions;
        this.discovery = discovery;
        this.json = json;
        this.planningResults = planningResults;
        this.shortsPipeline = planningEnabled ? ProductionPlanning.PIPELINE : ProductionDiscovery.PIPELINE;
    }

    @Transactional(readOnly = true)
    public ProductionRequestController.Planning planning(long actorId, long id) {
        requireOwner(mapper.byId(id), actorId);
        var results = planningResults.applied(id).stream().map(result -> {
            if (!PlanningJson.matches(result.bodyJson(), result.bodyHash())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Planning result integrity check failed.");
            }
            Object body = switch (result.stage()) {
                case RESEARCH -> json.readValue(result.bodyJson(), ProductionPlanning.Research.class);
                case SCRIPT -> json.readValue(result.bodyJson(), ProductionPlanning.Script.class);
                case SCENES -> json.readValue(result.bodyJson(), ProductionPlanning.Scenes.class);
                default -> throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
            };
            return new ProductionRequestController.PlanningResult(Long.toString(result.operationId()),
                    result.stage(), result.bodyHash(), body,
                    json.readValue(result.validationJson(), ProductionPlanning.Validation.class));
        }).toList();
        return new ProductionRequestController.Planning("FAKE", results);
    }

    Created create(long actorId, long channelId, String type, String key, Mode selectionMode, Integer candidateCount) {
        Mode mode = selectionMode == null ? Mode.AUTO : selectionMode;
        int count = candidateCount == null ? ProductionDiscovery.DEFAULT_CANDIDATES : candidateCount;
        boolean isDefault = mode == Mode.AUTO && count == ProductionDiscovery.DEFAULT_CANDIDATES;
        boolean discoveryEnabled = "SHORTS".equals(type);
        if (!discoveryEnabled && !isDefault) {
            throw ApiException.validation("selectionMode", "Discovery options are available for SHORTS only.");
        }
        String hash = fingerprint(actorId + ":" + channelId + ":" + type
                + (isDefault ? "" : ":discovery:" + mode.name() + ":" + count));
        try {
            return transactions.execute(() -> {
                ProductionRequestMapper.Request previous = mapper.byKey(key);
                if (previous != null) {
                    return replay(previous, hash);
                }
                control.shared();
                Channel channel = channels.lockShared(channelId);
                if (!"ACTIVE".equals(channel.status())) {
                    throw new ApiException(ErrorCode.POLICY_VIOLATION, "Channel is not active.");
                }
                BigDecimal limit = mapper.typeLimit(channelId, type);
                if (limit == null) {
                    throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "An active video budget is required.");
                }
                long configId = channels.latestConfigInTransaction(channelId).id();
                mapper.insert(channelId, configId, actorId, type,
                        discoveryEnabled ? shortsPipeline : "v2-fixed-1", key, hash, limit);
                ProductionRequestMapper.Request request = mapper.byKey(key);
                for (ProductionStage stage : ProductionStage.values()) {
                    mapper.addStep(request.id(), stage);
                }
                if (discoveryEnabled) {
                    discovery.initialize(request.id(), mode, count, channel.language());
                }
                return new Created(request, false);
            });
        } catch (DuplicateKeyException collision) {
            ProductionRequestMapper.Request previous = mapper.byKey(key);
            if (previous == null) {
                throw collision;
            }
            return replay(previous, hash);
        }
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<ProductionRequestController.ListItem> list(long actorId, PageQuery page) {
        return PageResponse.of(mapper.list(actorId, page.size(), page.offset()), page, mapper.count(actorId),
                row -> new ProductionRequestController.ListItem(Long.toString(row.id()), Long.toString(row.channelId()),
                        row.channelName(), row.selectedTitle(), row.status(), row.limitKrw().toPlainString(),
                        row.reservedKrw().toPlainString(), row.recognizedKrw().toPlainString(), row.createdAt()));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProductionRequestController.Detail get(long actorId, long id) {
        return detail(requireOwner(mapper.byId(id), actorId));
    }

    ProductionRequestController.Detail cancel(long actorId, long id) {
        return transactions.execute(() -> {
            ProductionRequestMapper.Request request = requireOwner(mapper.lock(id), actorId);
            ProductionStatus status = request.status();
            if (request.cancelRequestedAt() == null && status != ProductionStatus.SUCCEEDED
                    && status != ProductionStatus.FAILED && status != ProductionStatus.CANCELLED) {
                mapper.requestCancel(id);
                request = mapper.byId(id);
            }
            return detail(request);
        });
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProductionRequestController.Discovery discovery(long actorId, long id) {
        var request = requireOwner(mapper.byId(id), actorId);
        var batch = requireBatch(id);
        return discoveryView(request, batch);
    }

    ProductionRequestController.Discovery choose(long actorId, long id, int candidateId, int version, String token, boolean confirmReview) {
        return transactions.execute(() -> {
            var request = requireOwner(mapper.lock(id), actorId);
            var batch = requireBatch(id);
            if (!batchToken(id, version).equals(token)) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            }
            if (Objects.equals(batch.chosenCandidateId(), candidateId) && Objects.equals(batch.choiceVersion(), version)) {
                return discoveryView(request, batch);
            }
            if (batch.chosenCandidateId() != null || batch.version() != version) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            }
            if (!awaitingSelection(request, batch)) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            if (evaluations(batch).stream().noneMatch(candidate ->
                    candidate.candidateId() == candidateId && candidate.manuallySelectable()
                            && (!candidate.reviewRequired() || confirmReview))) {
                throw new ApiException(ErrorCode.POLICY_VIOLATION, "The candidate is not eligible for selection.");
            }
            if (discovery.choose(id, candidateId, version, actorId) != 1) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            }
            return discoveryView(request, requireBatch(id));
        });
    }

    private Batch requireBatch(long id) {
        var batch = discovery.read(id);
        if (batch == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return batch;
    }

    private boolean awaitingSelection(ProductionRequestMapper.Request request, Batch batch) {
        return request.status() == ProductionStatus.RUNNING && request.cancelRequestedAt() == null
                && batch.selectionMode() == Mode.MANUAL && batch.evaluationJson() != null
                && batch.chosenCandidateId() == null && batch.selectedCandidateId() == null
                && discovery.awaitingSelectionStage(request.id());
    }

    private ProductionRequestController.Discovery discoveryView(ProductionRequestMapper.Request request, Batch batch) {
        return new ProductionRequestController.Discovery(batch.selectionMode(), batch.candidateCount(), batch.version(),
                batchToken(request.id(), batch.version()), awaitingSelection(request, batch), evaluations(batch), batch.chosenCandidateId(),
                batch.selectedCandidateId(), batch.selectionReason());
    }

    private static String batchToken(long requestId, int version) {
        return CanonicalJson.sha256(List.of("discovery-batch", requestId, version));
    }

    private List<EvaluatedCandidate> evaluations(Batch batch) {
        return batch.evaluationJson() == null ? List.of()
                : List.of(json.readValue(batch.evaluationJson(), EvaluatedCandidate[].class));
    }

    private ProductionRequestController.Detail detail(ProductionRequestMapper.Request request) {
        var usage = mapper.usage(request.id());
        return new ProductionRequestController.Detail(Long.toString(request.id()), Long.toString(request.channelId()),
                request.contentType(), request.status(), idString(request.contentId()), idString(request.runId()),
                request.cancelRequestedAt(), request.limitKrw().toPlainString(), usage.reservedKrw().toPlainString(),
                usage.recognizedKrw().toPlainString(), mapper.steps(request.id()));
    }

    private static String idString(Long id) {
        return id == null ? null : id.toString();
    }

    private static ProductionRequestMapper.Request requireOwner(ProductionRequestMapper.Request request, long actorId) {
        if (request == null || request.requestedBy() != actorId) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return request;
    }

    private static Created replay(ProductionRequestMapper.Request request, String hash) {
        if (!request.requestHash().equals(hash)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
        }
        return new Created(request, true);
    }

    private static String fingerprint(String identity) {
        try {
            byte[] input = identity.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
