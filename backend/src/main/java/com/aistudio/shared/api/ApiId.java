package com.aistudio.shared.api;

/** Parses positive signed BIGINT identifiers represented as strings at the API boundary. */
public final class ApiId {

    public static final String PATTERN = "^[1-9][0-9]{0,18}$";

    private ApiId() {
    }

    public static long parse(String field, String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Return the same validation error for malformed or out-of-range input.
        }
        throw ApiException.validation(field, "must be a positive signed BIGINT identifier");
    }

    public static Long optional(String field, String value) {
        return value == null ? null : parse(field, value);
    }
}
