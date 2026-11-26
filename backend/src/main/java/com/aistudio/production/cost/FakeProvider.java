package com.aistudio.production.cost;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.aistudio.production.contract.ProductionCostPort.Evidence;
import com.aistudio.production.contract.ProductionCostPort.Outcome;
import com.aistudio.production.contract.ProductionCostPort.PricedRequest;

@Component
class FakeProvider {
    Evidence send(long id, PricedRequest request) {
        String ref = "fake:" + id;
        return switch (request.fakeScenario() == null ? "SUCCESS" : request.fakeScenario()) {
            case "UNKNOWN", "TIMEOUT" -> null;
            case "NOT_EXECUTED" -> new Evidence(Outcome.NOT_EXECUTED, ref, request.currency(),
                    BigDecimal.ZERO, null, null, "FAKE confirmed no execution");
            case "EXECUTED_FAILED" -> new Evidence(Outcome.EXECUTED_FAILED, ref, request.currency(),
                    request.maximumAmount(), null, null, "FAKE execution failed");
            case "OVERRUN" -> success(ref, request, request.maximumAmount().multiply(BigDecimal.TWO));
            default -> success(ref, request, request.maximumAmount());
        };
    }

    private Evidence success(String ref, PricedRequest request, BigDecimal amount) {
        String hash = com.aistudio.content.command.CanonicalJson.sha256(java.util.Map.of("result", ref));
        return new Evidence(Outcome.RESULT_ADOPTED, ref, request.currency(), amount, ref, hash, "FAKE response");
    }
}
