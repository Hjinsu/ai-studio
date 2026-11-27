package com.aistudio.production.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Maximum exposure used at both plan selection and the existing ledger permit boundary. */
public final class ProductionMediaBudget {
    public static final BigDecimal VIDEO_CAP_KRW = new BigDecimal("3000");

    private ProductionMediaBudget() {}

    public static BigDecimal requestLimit(BigDecimal configuredKrw) {
        return nonnegative(configuredKrw).min(VIDEO_CAP_KRW);
    }

    public static boolean permits(BigDecimal configuredKrw, BigDecimal recognizedKrw,
            BigDecimal unrecognizedExposureKrw, BigDecimal additionalMaximumKrw) {
        return nonnegative(recognizedKrw).add(nonnegative(unrecognizedExposureKrw))
                .add(nonnegative(additionalMaximumKrw)).compareTo(requestLimit(configuredKrw)) <= 0;
    }

    public static BigDecimal maximumKrw(BigDecimal maximumOriginal, BigDecimal krwPerUnit,
            BigDecimal maximumTaxAndFeesKrw) {
        if (nonnegative(krwPerUnit).signum() == 0) {
            throw new IllegalArgumentException("Missing exchange rate");
        }
        return nonnegative(maximumOriginal).multiply(krwPerUnit).add(nonnegative(maximumTaxAndFeesKrw))
                .setScale(6, RoundingMode.CEILING);
    }

    private static BigDecimal nonnegative(BigDecimal amount) {
        if (Objects.requireNonNull(amount).signum() < 0) {
            throw new IllegalArgumentException("Negative exposure");
        }
        return amount;
    }
}
