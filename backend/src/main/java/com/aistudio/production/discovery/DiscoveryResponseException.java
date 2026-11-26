package com.aistudio.production.discovery;

/**
 * A provider response that does not satisfy the discovery contract. The code is fixed and safe to store; the
 * message names the field only and never carries provider text.
 */
public class DiscoveryResponseException extends RuntimeException {
    public static final String CODE = "DISCOVERY_RESPONSE_INVALID";

    private final String field;

    public DiscoveryResponseException(String field, String problem) {
        super(field + ": " + problem);
        this.field = field;
    }

    public String field() {
        return field;
    }

    public String code() {
        return CODE;
    }
}
