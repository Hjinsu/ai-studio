package com.aistudio.production.cost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetReservationService;
import com.aistudio.budget.BudgetReservationService.DispatchBudget;
import com.aistudio.budget.BudgetReservationService.EventCommand;
import com.aistudio.budget.BudgetReservationService.ReserveCommand;
import com.aistudio.budget.Money;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.DiscoveryProvider;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionDiscovery;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.discovery.DiscoveryInputs;
import com.aistudio.production.discovery.DiscoveryResponseCodec;
import com.aistudio.production.discovery.DiscoveryResponseException;
import com.aistudio.production.discovery.DiscoveryResponseValidator;
import com.aistudio.production.discovery.persistence.ProductionDiscoveryMapper;
import com.aistudio.production.cost.ProductionCostMapper.Operation;
import com.aistudio.production.cost.ProductionCostMapper.Request;
import com.aistudio.production.cost.ProductionCostMapper.Step;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ProductionCostService implements ProductionCostPort {
    private record Dispatch(EffectResult replay, PricedRequest send) {}
    private record EvidenceRecord(String key, Evidence evidence) {}
    private record Completion(Evidence evidence, String body) {}

    private final ProductionCostMapper mapper;
    private final BudgetReservationService budget;
    private final BudgetControlLock control;
    private final LockRetryingTransactions transactions;
    private final FakeProvider provider;
    private final JsonMapper json;
    private final ProductionDiscoveryMapper discovery;
    private final ObjectProvider<DiscoveryProvider> discoveryProvider;

    ProductionCostService(ProductionCostMapper mapper, BudgetReservationService budget, BudgetControlLock control,
            LockRetryingTransactions transactions, FakeProvider provider, JsonMapper json,
            ProductionDiscoveryMapper discovery, ObjectProvider<DiscoveryProvider> discoveryProvider) {
        this.mapper = mapper;
        this.budget = budget;
        this.control = control;
        this.transactions = transactions;
        this.provider = provider;
        this.json = json;
        this.discovery = discovery;
        this.discoveryProvider = discoveryProvider;
    }

    @Override
    public EffectResult executeEffect(StepOwner owner, String effectKey, String requestHash, PricedRequest request) {
        return execute(owner, effectKey, requestHash, request, false,
                (id, priced) -> new Completion(provider.send(id, priced), null));
    }

    public EffectResult executeDiscovery(StepOwner owner, ProductionDiscovery.Input input, BigDecimal maximumAmount) {
        var generator = discoveryProvider.getIfAvailable();
        if (generator == null) {
            return result(Disposition.REJECTED);
        }
        Step step = mapper.step(owner.requestId(), owner.stepId());
        if (step == null || step.stage() != ProductionStage.DISCOVER || owner.requestId() != input.requestId()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String key = EffectKey.forInput(owner.requestId(), step.stage(), step.targetKey(), step.inputGeneration());
        var price = new PricedRequest("KRW", maximumAmount, null);
        String hash = DiscoveryInputs.sha256(input);
        return execute(owner, key, hash, price, true, (id, priced) -> {
            String body;
            try {
                var response = generator.generate(input);
                DiscoveryResponseValidator.validate(input, response);
                body = DiscoveryResponseCodec.canonicalJson(response);
            } catch (DiscoveryResponseException | DiscoveryProvider.InvalidResponse e) {
                return new Completion(new Evidence(Outcome.EXECUTED_FAILED, "fake:" + id, "KRW",
                        priced.maximumAmount(), null, null, "FAKE discovery response format rejected"), null);
            }
            return new Completion(new Evidence(Outcome.RESULT_ADOPTED, "fake:" + id, "KRW", priced.maximumAmount(),
                    "discovery:" + owner.requestId(), CanonicalJson.sha256Hex(body), "FAKE discovery response"), body);
        });
    }

    private EffectResult execute(StepOwner owner, String effectKey, String requestHash, PricedRequest request,
            boolean discoveryEffect, BiFunction<Long, PricedRequest, Completion> send) {
        if (!hash(requestHash)) {
            throw ApiException.validation("requestHash", "must be a lowercase SHA-256");
        }
        if (request == null || request.maximumAmount() == null || request.maximumAmount().signum() <= 0) {
            throw ApiException.validation("maximumAmount", "must be greater than zero");
        }
        PricedRequest priced = new PricedRequest(Money.currency("currency", request.currency()),
                Money.original(request.maximumAmount()), request.fakeScenario());
        String pricedHash = CanonicalJson.sha256(Map.of("requestHash", requestHash, "currency", priced.currency(),
                "maximumAmount", priced.maximumAmount().toPlainString(),
                "fakeScenario", Objects.toString(priced.fakeScenario(), "SUCCESS")));
        Operation operation;
        try {
            operation = transactions.execute(() -> reserve(owner, effectKey, pricedHash, priced, discoveryEffect));
        } catch (ApiException e) {
            if (e.errorCode() == ErrorCode.BUDGET_EXCEEDED || e.errorCode() == ErrorCode.POLICY_VIOLATION
                    || e.errorCode() == ErrorCode.PREREQUISITE_NOT_MET) {
                return result(Disposition.REJECTED);
            }
            throw e;
        }
        if (operation == null) {
            return result(Disposition.REJECTED);
        }
        // Only the transaction that commits the dispatch transition returns permission to send.
        Dispatch dispatch = transactions.execute(() -> permit(owner, operation.id(), priced));
        if (dispatch.replay() != null) {
            return dispatch.replay();
        }
        Completion completion;
        try {
            completion = send.apply(operation.id(), dispatch.send());
        } catch (RuntimeException e) {
            quarantine(operation);
            if (discoveryEffect) {
                return result(Disposition.RECOVERY_REQUIRED);
            }
            throw e;
        }
        if (completion.evidence() == null) {
            quarantine(operation);
            return result(Disposition.RECOVERY_REQUIRED);
        }
        return new EffectResult(Disposition.RECEIPT,
                reconcile(operation.id(), "fake-response", completion.evidence(), completion.body()));
    }

    public boolean ownsDiscoveryReceipt(StepOwner owner, Receipt expected) {
        Step step = requireStep(owner);
        Operation operation = mapper.operation(expected.operationId());
        return step.stage() == ProductionStage.DISCOVER && operation != null
                && operation.productionRequestId() == owner.requestId() && operation.productionStepId() == step.id()
                && "SETTLED".equals(operation.status()) && !operation.reservationOpen()
                && receipt(operation).equals(expected);
    }

    public boolean appliedDiscoveryReceipt(long requestId, long operationId, String responseHash) {
        Operation operation = mapper.operation(operationId);
        if (operation == null || operation.productionRequestId() != requestId || !operation.applied()
                || !"SETTLED".equals(operation.status()) || operation.reservationOpen()) {
            return false;
        }
        Step step = mapper.step(requestId, operation.productionStepId());
        var receipt = receipt(operation);
        return step != null && step.stage() == ProductionStage.DISCOVER && receipt.outcome() == Outcome.RESULT_ADOPTED
                && ("discovery:" + requestId).equals(receipt.resultRef()) && responseHash.equals(receipt.resultHash());
    }

    private Operation reserve(StepOwner owner, String key, String hash, PricedRequest priced, boolean discoveryEffect) {
        control.shared();
        Request request = requireRequest(owner.requestId());
        Step step = requireStep(owner);
        if (discoveryEffect != (ProductionDiscovery.supportsPipeline(request.pipelineVersion())
                && step.stage() == ProductionStage.DISCOVER)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!EffectKey.forInput(request.id(), step.stage(), step.targetKey(), step.inputGeneration()).equals(key)) {
            throw ApiException.validation("effectKey", "does not identify the owned step input");
        }
        Operation previous = mapper.byEffect(key);
        if (previous != null) {
            if (!previous.requestHash().equals(hash) || !previous.originalCurrency().equals(priced.currency())
                    || previous.estimateOriginal().compareTo(priced.maximumAmount()) != 0) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The effect request changed.");
            }
            return previous;
        }
        if ((discoveryEffect && step.inputGeneration() != 1) || request.cancelled() || request.spendingBlocked()
                || !"RUNNING".equals(step.status()) || !mapper.outstanding(request.id()).isEmpty()) {
            return null;
        }
        if (mapper.releasedGenerations(request.id(), step.stage(), step.targetKey(), step.inputGeneration())
                != step.inputGeneration() - 1) {
            return null;
        }
        BigDecimal current = mapper.committedKrw(request.id());
        long reservation = budget.createProductionReservation(new ReserveCommand("production:" + key,
                request.channelId(), "PRODUCTION_" + step.stage().name(), priced.currency(), priced.maximumAmount()));
        BigDecimal limit = limit(request);
        if (limit == null || current.add(mapper.estimateKrw(reservation)).compareTo(limit) > 0) {
            throw new ApiException(ErrorCode.BUDGET_EXCEEDED, "The production request exceeds its budget.");
        }
        requireStep(owner);
        mapper.reserve(request.id(), step.id(), key, hash, reservation);
        return mapper.byEffect(key);
    }

    private Dispatch permit(StepOwner owner, long id, PricedRequest priced) {
        control.shared();
        Request request = requireRequest(owner.requestId());
        Step step = requireStep(owner);
        Operation operation = mapper.operation(id);
        if ("SETTLED".equals(operation.status())) {
            return new Dispatch(new EffectResult(Disposition.RECEIPT, receipt(operation)), null);
        }
        if (operation.evidenceHash() != null) {
            return new Dispatch(new EffectResult(Disposition.RECEIPT, finish(request, operation)), null);
        }
        if (operation.dispatched()) {
            return new Dispatch(result(Disposition.RECOVERY_REQUIRED), null);
        }
        BigDecimal limit = limit(request);
        DispatchBudget decision = request.cancelled() ? DispatchBudget.BLOCKED
                : budget.checkProductionDispatch(request.channelId(), operation.reservationId());
        if (request.cancelled() || decision == DispatchBudget.PERIOD_ENDED) {
            String reason = request.cancelled() ? "CANCELLED_BEFORE_DISPATCH" : "BUDGET_PERIOD_ENDED";
            Evidence evidence = new Evidence(Outcome.NOT_EXECUTED, null, operation.originalCurrency(),
                    BigDecimal.ZERO, null, null, reason);
            requireStep(owner);
            save(operation, "local-no-dispatch", normalize(operation, "local-no-dispatch", evidence));
            return new Dispatch(new EffectResult(Disposition.RECEIPT, finish(request, mapper.operation(id))), null);
        }
        if (request.spendingBlocked() || decision != DispatchBudget.ALLOWED || limit == null
                || mapper.committedKrw(request.id()).compareTo(limit) > 0
                || !"RUNNING".equals(step.status())
                || mapper.outstanding(request.id()).stream().anyMatch(other -> other.id() != id)) {
            return new Dispatch(result(Disposition.RECOVERY_REQUIRED), null);
        }
        if (mapper.dispatch(id, owner.leaseOwner(), owner.leaseEpoch()) != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The dispatch lease is no longer current.");
        }
        return new Dispatch(null, priced);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CostState readCostState(long requestId) {
        // Mapper reads participate in the executor transaction when called from completion.
        Boolean blocked = mapper.spendingBlocked(requestId);
        if (blocked == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<Receipt> receipts = new ArrayList<>();
        boolean unresolved = false;
        for (Operation operation : mapper.outstanding(requestId)) {
            unresolved |= !"SETTLED".equals(operation.status()) || operation.reservationOpen();
            if ("SETTLED".equals(operation.status()) && !operation.applied()) {
                receipts.add(receipt(operation));
            }
        }
        return new CostState(unresolved, blocked, receipts);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReceiptApplied(StepOwner owner, long operationId) {
        requireRequest(owner.requestId());
        requireStep(owner);
        Operation operation = mapper.operation(operationId);
        if (operation == null || operation.productionStepId() != owner.stepId()
                || operation.productionRequestId() != owner.requestId() || !"SETTLED".equals(operation.status())
                || operation.reservationOpen()) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Receipt does not belong to the owned step.");
        }
        mapper.apply(operationId);
    }

    @Override
    public Receipt reconcile(long operationId, String evidenceKey, Evidence evidence) {
        return reconcile(operationId, evidenceKey, evidence, null);
    }

    private Receipt reconcile(long operationId, String evidenceKey, Evidence evidence, String body) {
        Long requestId = mapper.requestId(operationId);
        if (requestId == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        boolean accepted = transactions.execute(() -> {
            control.shared();
            Request request = requireRequest(requestId);
            Operation operation = mapper.operation(operationId);
            Evidence normalized = normalize(operation, evidenceKey, evidence);
            if (operation.evidenceHash() != null && !operation.evidenceHash().equals(evidenceHash(normalized))) {
                mapper.blockSpending(requestId);
                return false;
            }
            Step step = mapper.step(requestId, operation.productionStepId());
            if (ProductionDiscovery.supportsPipeline(request.pipelineVersion()) && step.stage() == ProductionStage.DISCOVER
                    && normalized.outcome() == Outcome.RESULT_ADOPTED) {
                if (operation.evidenceHash() == null) {
                    if (body == null || discovery.saveResponse(requestId, operationId, body, normalized.resultHash()) != 1) {
                        throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Discovery response is required.");
                    }
                }
                var stored = discovery.generated(requestId);
                if (stored == null || stored.operationId() != operationId
                        || !normalized.resultHash().equals(stored.responseHash())
                        || !normalized.resultHash().equals(CanonicalJson.sha256Hex(stored.responseJson()))
                        || !("discovery:" + requestId).equals(normalized.resultRef())) {
                    throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Discovery evidence does not match.");
                }
            }
            if (operation.evidenceHash() == null) {
                save(operation, evidenceKey, normalized);
            }
            return true;
        });
        if (!accepted) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Conflicting final provider evidence.");
        }
        // A crash between transactions leaves evidence available for settlement without another send.
        return transactions.execute(() -> {
            control.shared();
            Request request = requireRequest(requestId);
            return finish(request, mapper.operation(operationId));
        });
    }

    private void save(Operation operation, String key, Evidence evidence) {
        mapper.saveEvidence(operation.id(), evidenceHash(evidence), json.writeValueAsString(new EvidenceRecord(key, evidence)));
    }

    private Receipt finish(Request request, Operation operation) {
        if ("SETTLED".equals(operation.status())) {
            return receipt(operation);
        }
        Evidence evidence = storedEvidence(operation);
        long event = budget.settleProduction(new EventCommand(operation.reservationId(),
                "production:settle:" + operation.id(), evidence.amount(), evidence.externalRef(), null,
                null, evidence.proof(), null, Actor.system()), evidence.outcome() == Outcome.NOT_EXECUTED);
        if (evidence.amount().compareTo(operation.estimateOriginal()) > 0) {
            mapper.blockSpending(request.id());
        }
        mapper.settle(operation.id(), evidence.outcome().name(), event);
        return receipt(mapper.operation(operation.id()));
    }

    private void quarantine(Operation operation) {
        transactions.run(() -> {
            requireRequest(operation.productionRequestId());
            mapper.unknown(operation.id());
        });
    }

    private Receipt receipt(Operation operation) {
        Evidence evidence = storedEvidence(operation);
        return new Receipt(operation.id(), Outcome.valueOf(operation.receiptOutcome()), evidence.resultRef(),
                evidence.resultHash(), operation.receiptEventId());
    }

    private Evidence storedEvidence(Operation operation) {
        return json.readValue(operation.evidenceJson(), EvidenceRecord.class).evidence();
    }

    private Request requireRequest(long id) {
        Request request = mapper.lockRequest(id);
        if (request == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return request;
    }

    private Step requireStep(StepOwner owner) {
        Step step = mapper.step(owner.requestId(), owner.stepId());
        if (step == null || !mapper.owns(owner.requestId(), owner.leaseOwner(), owner.leaseEpoch())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The step lease is not current.");
        }
        return step;
    }

    private BigDecimal limit(Request request) {
        BigDecimal active = mapper.typeLimit(request.channelId(), request.contentType());
        return active == null ? null : active.min(request.limitKrw());
    }

    private static Evidence normalize(Operation operation, String key, Evidence evidence) {
        if (key == null || key.isBlank() || key.length() > 128 || evidence == null || evidence.outcome() == null
                || evidence.amount() == null || evidence.amount().signum() < 0
                || evidence.proof() == null || evidence.proof().isBlank() || evidence.proof().length() > 2000
                || (evidence.externalRef() != null && evidence.externalRef().length() > 128)
                || (evidence.resultRef() != null && evidence.resultRef().length() > 512)) {
            throw ApiException.validation("evidence", "complete bounded provider evidence is required");
        }
        String currency = Money.currency("currency", evidence.currency());
        BigDecimal amount = Money.original(evidence.amount());
        boolean ran = evidence.outcome() != Outcome.NOT_EXECUTED;
        if (!operation.originalCurrency().equals(currency)
                || (ran && (!operation.dispatched() || evidence.externalRef() == null || evidence.externalRef().isBlank()))
                || (!ran && amount.signum() != 0)
                || (evidence.outcome() == Outcome.RESULT_ADOPTED
                        && (evidence.resultRef() == null || evidence.resultRef().isBlank() || !hash(evidence.resultHash())))
                || (evidence.outcome() != Outcome.RESULT_ADOPTED
                        && (evidence.resultRef() != null || evidence.resultHash() != null))) {
            throw ApiException.validation("evidence", "outcome, cost and result do not match the operation");
        }
        return new Evidence(evidence.outcome(), evidence.externalRef(), currency, amount, evidence.resultRef(),
                evidence.resultHash(), evidence.proof());
    }

    private static String evidenceHash(Evidence evidence) {
        return CanonicalJson.sha256(Map.of("outcome", evidence.outcome().name(), "currency", evidence.currency(),
                "amount", evidence.amount().toPlainString(), "externalRef", Objects.toString(evidence.externalRef(), ""),
                "resultRef", Objects.toString(evidence.resultRef(), ""), "resultHash", Objects.toString(evidence.resultHash(), ""),
                "proof", evidence.proof()));
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static EffectResult result(Disposition disposition) {
        return new EffectResult(disposition, null);
    }
}
