package com.aistudio.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

import com.aistudio.shared.api.ApiException;

/**
 * 금액·환율 정밀도 규칙 (ADR-030). 원본 DECIMAL(24,12), 환율 DECIMAL(24,10), KRW DECIMAL(24,6).
 * DB는 초과 자릿수를 경고 없이 반올림하므로 입력 단계에서 거부한다.
 */
public final class Money {

    public static final int ORIGINAL_SCALE = 12;
    public static final int RATE_SCALE = 10;
    public static final int KRW_SCALE = 6;
    public static final String KRW = "KRW";

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

    private Money() {
    }

    public static BigDecimal parse(String field, String value, int maxScale, int maxIntegerDigits) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw ApiException.validation(field, "must be a decimal string");
        }
        BigDecimal number = new BigDecimal(value);
        if (number.scale() > maxScale) {
            throw ApiException.validation(field, "must have at most " + maxScale + " decimal places");
        }
        if (number.precision() - number.scale() > maxIntegerDigits) {
            throw ApiException.validation(field, "is too large");
        }
        return number;
    }

    public static BigDecimal original(BigDecimal value) {
        return requireScale(value, ORIGINAL_SCALE);
    }

    public static BigDecimal krw(BigDecimal value) {
        return requireScale(value, KRW_SCALE);
    }

    public static BigDecimal rate(BigDecimal value) {
        return requireScale(value, RATE_SCALE);
    }

    private static BigDecimal requireScale(BigDecimal value, int scale) {
        if (value.stripTrailingZeros().scale() > scale) {
            throw ApiException.validation("amount", "must have at most " + scale + " decimal places");
        }
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    /** 예약: 소수 6자리 CEILING. */
    public static BigDecimal reserveKrw(BigDecimal original, BigDecimal rate) {
        return original.multiply(rate).setScale(KRW_SCALE, RoundingMode.CEILING);
    }

    /** 정산 환산: 소수 6자리 HALF_UP(부호 대칭). */
    public static BigDecimal settleKrw(BigDecimal original, BigDecimal rate) {
        return original.multiply(rate).setScale(KRW_SCALE, RoundingMode.HALF_UP);
    }

    public static String currency(String field, String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw ApiException.validation(field, "must be an ISO 4217 uppercase code");
        }
        return currency;
    }

    public static String format(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
