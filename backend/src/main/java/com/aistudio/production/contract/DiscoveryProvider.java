package com.aistudio.production.contract;

import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;

/** The cost adapter invokes this after obtaining a committed dispatch permit. */
public interface DiscoveryProvider {
    Response generate(Input input);
}
