package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;

/**
 * V2-01 FAKE stage work. Stages that will call paid providers go through the cost port with a FAKE priced
 * request; subtitle and render are local. Produces no media and must never be treated as a finished product.
 */
public class FakeStageWork implements StageWork {
    static final String FAKE_SCENARIO = "SUCCESS";
    static final int FAKE_DURATION_SECONDS = 45;
    private static final Set<ProductionStage> LOCAL = EnumSet.of(ProductionStage.SUBTITLE, ProductionStage.RENDER);

    private final BigDecimal effectMaxKrw;

    public FakeStageWork(BigDecimal effectMaxKrw) {
        if (effectMaxKrw == null || effectMaxKrw.signum() <= 0) {
            throw new IllegalArgumentException("FAKE effect maximum must be a positive KRW amount");
        }
        this.effectMaxKrw = effectMaxKrw;
    }

    @Override
    public Kind kind(ProductionStage stage) {
        if (stage == ProductionStage.SELECT) {
            return Kind.SELECT;
        }
        return LOCAL.contains(stage) ? Kind.LOCAL : Kind.EFFECT;
    }

    @Override
    public String runLocal(StepContext context) {
        return "fake:" + context.stage().name().toLowerCase(Locale.ROOT) + ":" + context.requestId();
    }

    @Override
    public ProductionCostPort.PricedRequest price(StepContext context) {
        return new ProductionCostPort.PricedRequest("KRW", effectMaxKrw, FAKE_SCENARIO);
    }

    @Override
    public ProductionSelectionPort.Selection select(StepContext context) {
        String fingerprint = EffectRequestHash.sha256("fake-selection:v1:" + context.requestId());
        return new ProductionSelectionPort.Selection(fingerprint, "FAKE topic " + context.requestId(),
                "FAKE brief for fixed-stage executor verification", FAKE_DURATION_SECONDS);
    }
}
