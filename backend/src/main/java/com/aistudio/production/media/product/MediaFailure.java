package com.aistudio.production.media.product;

/**
 * A product media step stopped. {@code BLOCKED} failures are integrity, rights, profile or quality violations that
 * must not be retried automatically; {@code RETRY} failures are transient local faults (process, I/O) that the
 * executor may retry with a new attempt. The code is a stable upper-case identifier shown to operators.
 */
final class MediaFailure extends RuntimeException {
    enum Kind { BLOCKED, RETRY }

    private final Kind kind;
    private final String code;

    private MediaFailure(Kind kind, String code, String detail, Throwable cause) {
        super(code + ": " + detail, cause);
        if (!code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid media failure code");
        }
        this.kind = kind;
        this.code = code;
    }

    static MediaFailure blocked(String code, String detail) {
        return new MediaFailure(Kind.BLOCKED, code, detail, null);
    }

    static MediaFailure retry(String code, String detail, Throwable cause) {
        return new MediaFailure(Kind.RETRY, code, detail, cause);
    }

    Kind kind() {
        return kind;
    }

    String code() {
        return code;
    }
}
