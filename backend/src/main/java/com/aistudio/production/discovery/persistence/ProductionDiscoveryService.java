package com.aistudio.production.discovery.persistence;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.channel.ChannelService;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.Outcome;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionDiscovery;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.RealMediaProfile;
import com.aistudio.production.cost.ProductionCostMapper;
import com.aistudio.production.cost.ProductionCostService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.LockRetryingTransactions;
import com.aistudio.topic.TopicNormalizer;

import tools.jackson.databind.json.JsonMapper;

@Service
class ProductionDiscoveryService implements DiscoveryExecutionPort {
    private final ProductionDiscoveryMapper batches;
    private final DiscoveryCatalogMapper catalog;
    private final ProductionCostMapper requests;
    private final ProductionCostService cost;
    private final ChannelService channels;
    private final JsonMapper json;
    private final LockRetryingTransactions transactions;
    private final BigDecimal price;

    ProductionDiscoveryService(ProductionDiscoveryMapper batches, DiscoveryCatalogMapper catalog,
            ProductionCostMapper requests, ProductionCostService cost, ChannelService channels, JsonMapper json,
            LockRetryingTransactions transactions,
            @Value("${ai-studio.production.executor.fake-effect-max-krw:1}") BigDecimal price) {
        this.batches = batches;
        this.catalog = catalog;
        this.requests = requests;
        this.cost = cost;
        this.channels = channels;
        this.json = json;
        this.transactions = transactions;
        this.price = price;
    }

    @Override
    @Transactional(readOnly = true)
    public Input input(long requestId) {
        var snapshot = batches.snapshot(requestId);
        if (snapshot == null) {
            return null;
        }
        var config = channels.configSnapshot(snapshot.channelId(), snapshot.channelConfigId());
        var prohibited = config.providerOptionsJson() == null ? null
                : json.readTree(config.providerOptionsJson()).path("discovery").path("prohibitedTopics");
        List<String> topics = prohibited != null && prohibited.isArray()
                ? prohibited.valueStream().filter(node -> node.isString()).map(node -> node.asString()).toList()
                : List.of();
        return new Input(requestId, snapshot.channelConfigId(), snapshot.language(), snapshot.candidateCount(),
                config.shortsMinSeconds(), config.shortsMaxSeconds(), config.contentStrategy(), config.targetAudience(),
                topics, snapshot.rulesVersion());
    }

    @Override
    public boolean manualMode(long requestId) {
        var batch = batches.read(requestId);
        return batch != null && batch.selectionMode() == ProductionDiscovery.Mode.MANUAL;
    }

    @Override
    public EffectResult discover(StepOwner owner, Input input) {
        if (input == null || !input.equals(transactions.execute(() -> input(owner.requestId())))) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Discovery input does not match its snapshot.");
        }
        return cost.executeDiscovery(owner, input, price);
    }

    @Override
    public Response response(StepOwner owner, Receipt receipt) {
        if (receipt == null || receipt.outcome() != Outcome.RESULT_ADOPTED
                || !("discovery:" + owner.requestId()).equals(receipt.resultRef())
                || !cost.ownsDiscoveryReceipt(owner, receipt)) {
            throw invalid();
        }
        return storedResponse(owner.requestId(), receipt.operationId(), receipt.resultHash());
    }

    @Override
    @Transactional
    public List<CatalogEntry> catalog(long requestId, List<Candidate> candidates) {
        var snapshot = batches.snapshot(requestId);
        if (snapshot == null) {
            throw invalid();
        }
        long channelId = snapshot.channelId();
        channels.lockShared(channelId);
        var hashes = candidates.stream().map(candidate -> TopicNormalizer.hash(TopicNormalizer.VERSION,
                TopicNormalizer.normalize(candidate.title()))).distinct().toList();
        var terms = candidates.stream().flatMap(candidate -> Stream.of(candidate.coreTopic(), candidate.centralQuestion()))
                .map(TopicNormalizer::normalize).flatMap(value -> Stream.of(value.split("[^a-z0-9]+")))
                .filter(term -> term.length() >= 4)
                .filter(term -> !List.of("does", "what", "when", "with", "that", "this", "from", "which").contains(term))
                .distinct().limit(50).toList();
        var entries = new LinkedHashSet<CatalogEntry>();
        if (!hashes.isEmpty()) {
            entries.addAll(catalog.exact(channelId, hashes));
        }
        entries.addAll(catalog.recent(channelId));
        if (!terms.isEmpty()) {
            entries.addAll(catalog.related(channelId, terms));
        }
        return List.copyOf(entries);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyEvaluation(StepOwner owner, Receipt receipt, List<EvaluatedCandidate> evaluations) {
        lockOwned(owner, ProductionStage.DISCOVER);
        Response response = response(owner, receipt);
        checkEvaluations(response, evaluations);
        String evaluation = json.writeValueAsString(evaluations);
        if (batches.evaluate(owner.requestId(), receipt.operationId(), evaluation) != 1
                && !evaluation.equals(batches.read(owner.requestId()).evaluationJson())) {
            throw invalid();
        }
        cost.markReceiptApplied(owner, receipt.operationId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyWorker(StepOwner owner, long exchangeId, String body, String hash,
            List<EvaluatedCandidate> evaluations) {
        var request = lockOwned(owner, ProductionStage.DISCOVER);
        if (!RealMediaProfile.PIPELINE.equals(request.pipelineVersion()) || request.cancelled()
                || !CanonicalJson.sha256Hex(body).equals(hash)) {
            throw invalid();
        }
        checkEvaluations(json.readValue(body, Response.class), evaluations);
        if (batches.adoptWorker(owner.requestId(), exchangeId, body, hash,
                json.writeValueAsString(evaluations)) != 1) {
            throw invalid();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SelectionBatch lockForSelection(StepOwner owner) {
        var request = lockOwned(owner, ProductionStage.SELECT);
        if (request.cancelled() || request.spendingBlocked()) {
            throw invalid();
        }
        channels.lockForSelection(request.channelId());
        if (!requests.owns(owner.requestId(), owner.leaseOwner(), owner.leaseEpoch())) {
            throw invalid();
        }
        var batch = batches.read(request.id());
        if (batch == null || batch.evaluationJson() == null) {
            throw invalid();
        }
        var generated = batches.generated(request.id());
        if (!accepted(request.id(), generated)) {
            throw invalid();
        }
        var evaluations = List.of(json.readValue(batch.evaluationJson(), EvaluatedCandidate[].class));
        checkEvaluations(selectedResponse(request.id(), generated), evaluations);
        return new SelectionBatch(batch.selectionMode(), batch.version(), batch.chosenCandidateId(), evaluations, generated.responseHash());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSelection(StepOwner owner, int version, int candidateId, String rationale) {
        recordSelection(owner, version, candidateId, rationale, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSelection(StepOwner owner, int version, int candidateId, String rationale,
            List<EvaluatedCandidate> rechecked) {
        var selection = lockForSelection(owner);
        var request = requests.lockRequest(owner.requestId());
        var batch = batches.read(owner.requestId());
        if (batch.selectedCandidateId() != null && batch.selectedCandidateId() == candidateId
                && batch.version() == version + 1 && Objects.equals(batch.selectionReason(), rationale)) {
            return;
        }
        if (rechecked != null) {
            var generated = batches.generated(owner.requestId());
            checkEvaluations(selectedResponse(owner.requestId(), generated), rechecked);
        }
        var current = rechecked == null ? selection.candidates() : rechecked;
        var candidate = current.stream().filter(item -> item.candidateId() == candidateId
                && (selection.mode() == ProductionDiscovery.Mode.AUTO ? item.eligible() : selection.permitsManualChoice(item)))
                .findFirst().orElseThrow(ProductionDiscoveryService::invalid);
        if (request.selectionHash() == null || selection.version() != version
                || rationale == null || rationale.isBlank() || rationale.length() > 2000
                || batches.selectCandidate(owner.requestId(), version, candidateId, rationale,
                        candidate.candidate().title().strip(), rechecked == null ? null : json.writeValueAsString(rechecked)) != 1) {
            throw invalid();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateManualChoice(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        var selection = lockForSelection(owner);
        var generated = batches.generated(owner.requestId());
        checkEvaluations(selectedResponse(owner.requestId(), generated), evaluations);
        if (selection.chosenCandidateId() == null || selection.version() != version
                || evaluations.stream().noneMatch(item -> item.candidateId() == selection.chosenCandidateId()
                        && !selection.permitsManualChoice(item))
                || batches.invalidateChoice(owner.requestId(), version, json.writeValueAsString(evaluations)) != 1) {
            throw invalid();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordExhaustedSelection(StepOwner owner, int version, List<EvaluatedCandidate> evaluations) {
        var selection = lockForSelection(owner);
        var generated = batches.generated(owner.requestId());
        checkEvaluations(selectedResponse(owner.requestId(), generated), evaluations);
        if (selection.mode() != ProductionDiscovery.Mode.AUTO || selection.version() != version
                || evaluations.stream().anyMatch(EvaluatedCandidate::eligible)
                || batches.exhaustSelection(owner.requestId(), version, json.writeValueAsString(evaluations)) != 1) {
            throw invalid();
        }
    }

    private ProductionCostMapper.Request lockOwned(StepOwner owner, ProductionStage expectedStage) {
        var request = requests.lockRequest(owner.requestId());
        var step = requests.step(owner.requestId(), owner.stepId());
        if (request == null || !(ProductionDiscovery.supportsPipeline(request.pipelineVersion()) || RealMediaProfile.PIPELINE.equals(request.pipelineVersion()))
                || step == null || step.stage() != expectedStage
                || !requests.owns(owner.requestId(), owner.leaseOwner(), owner.leaseEpoch())) {
            throw invalid();
        }
        return request;
    }

    private Response storedResponse(long requestId, long operationId, String hash) {
        var generated = batches.generated(requestId);
        if (generated == null || !Objects.equals(generated.operationId(), operationId)
                || !generated.responseHash().equals(hash) || !CanonicalJson.sha256Hex(generated.responseJson()).equals(hash)) {
            throw invalid();
        }
        return json.readValue(generated.responseJson(), Response.class);
    }

    private boolean accepted(long requestId, ProductionDiscoveryMapper.Generated generated) {
        return generated != null && (generated.operationId() != null
                ? cost.appliedDiscoveryReceipt(requestId, generated.operationId(), generated.responseHash())
                : generated.workerExchangeId() != null && generated.workerApplied());
    }

    private Response selectedResponse(long requestId, ProductionDiscoveryMapper.Generated generated) {
        if (!accepted(requestId, generated)
                || !CanonicalJson.sha256Hex(generated.responseJson()).equals(generated.responseHash())) {
            throw invalid();
        }
        return json.readValue(generated.responseJson(), Response.class);
    }

    private static void checkEvaluations(Response response, List<EvaluatedCandidate> evaluations) {
        int count = response.candidates().size();
        if (evaluations == null || evaluations.size() != count
                || evaluations.stream().map(EvaluatedCandidate::candidateId).distinct().count() != count
                || evaluations.stream().anyMatch(item -> item.candidateId() < 1 || item.candidateId() > count
                        || !response.candidates().get(item.candidateId() - 1).equals(item.candidate()))) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Discovery state does not match the owned step.");
    }
}
