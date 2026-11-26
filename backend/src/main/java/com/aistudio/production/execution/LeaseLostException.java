package com.aistudio.production.execution;

/** The worker no longer holds a valid lease (expired, replaced or released). No state change is allowed. */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException(long requestId) {
        super("Lease is no longer valid for production request " + requestId);
    }
}
