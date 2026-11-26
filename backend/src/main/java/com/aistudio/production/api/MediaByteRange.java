package com.aistudio.production.api;

import java.math.BigInteger;
import java.util.regex.Pattern;

record MediaByteRange(int status, long start, long end) {
    private static final Pattern SINGLE = Pattern.compile("bytes=([0-9]*)-([0-9]*)",
            Pattern.CASE_INSENSITIVE);

    static MediaByteRange parse(String header, long length) {
        var match = SINGLE.matcher(header == null ? "" : header.trim());
        if (!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty())) {
            return new MediaByteRange(200, 0, length - 1);
        }
        BigInteger size = BigInteger.valueOf(length);
        String left = match.group(1);
        String right = match.group(2);
        if (left.isEmpty()) {
            long suffix = new BigInteger(right).min(size).longValueExact();
            return suffix == 0 ? unsatisfiable() : new MediaByteRange(206, length - suffix, length - 1);
        }
        BigInteger start = new BigInteger(left);
        BigInteger end = right.isEmpty() ? size.subtract(BigInteger.ONE) : new BigInteger(right);
        if (start.compareTo(size) >= 0 || start.compareTo(end) > 0) {
            return unsatisfiable();
        }
        return new MediaByteRange(206, start.longValueExact(),
                end.min(size.subtract(BigInteger.ONE)).longValueExact());
    }

    private static MediaByteRange unsatisfiable() {
        return new MediaByteRange(416, 0, -1);
    }

    long count() {
        return end - start + 1;
    }
}
