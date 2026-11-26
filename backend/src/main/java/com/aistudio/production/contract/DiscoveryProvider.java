package com.aistudio.production.contract;

import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;

/** The cost adapter invokes this after obtaining a committed dispatch permit. */
public interface DiscoveryProvider {
    Response generate(Input input);

    /** The FAKE operation completed and its response could not be decoded. */
    class InvalidResponse extends RuntimeException {
        public InvalidResponse() {
            this("Invalid discovery response.");
        }

        protected InvalidResponse(String message) {
            super(message);
        }
    }
}
