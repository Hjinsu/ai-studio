package com.aistudio.production.execution;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.aistudio.production.contract.DiscoveryProvider;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.discovery.FakeDiscoveryProvider;

/** FAKE discovery provider whose response a test can script. Only the provider is replaced; ports stay real. */
final class ScriptedDiscoveryProvider implements DiscoveryProvider {
    private final FakeDiscoveryProvider fake = new FakeDiscoveryProvider();

    volatile Function<Input, Response> responses = fake::generate;
    /** A provider error after dispatch: the cost adapter must quarantine it as an unknown outcome. */
    volatile boolean failAfterDispatch;
    final AtomicInteger generations = new AtomicInteger();

    void reset() {
        responses = fake::generate;
        failAfterDispatch = false;
        generations.set(0);
    }

    @Override
    public Response generate(Input input) {
        generations.incrementAndGet();
        if (failAfterDispatch) {
            throw new IllegalStateException("simulated provider connection loss after dispatch");
        }
        return responses.apply(input);
    }
}
