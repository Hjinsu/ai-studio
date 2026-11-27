package com.aistudio.production.contract;

import com.aistudio.audit.AuditService.Actor;
import com.aistudio.production.contract.MediaEffectPort.Item;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionMediaSupplier.Quote;

/** Paid execution boundary. Calls use a frozen, independently approved quote. */
public interface RealMediaEffectPort {
    EffectResult executeReal(Item item, Quote quote, ProductionMediaSupplier supplier);

    /** Authenticated operations tooling attaches independently verified submission evidence without redispatch. */
    void attachKnownJob(long operationId, ProductionMediaSupplier.JobId job, String evidenceSha256, Actor actor);

    /** Only polls or retrieves an existing job (or replays a receipt); never submits. */
    EffectResult recoverReal(Item item, ProductionMediaSupplier supplier);
}
