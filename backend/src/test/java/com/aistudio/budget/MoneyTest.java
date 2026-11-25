package com.aistudio.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

class MoneyTest {

    @Test
    void rejectsTooManyDecimalPlacesInsteadOfRounding() {
        assertThatThrownBy(() -> Money.parse("rate", "1400.12345678901", Money.RATE_SCALE, 14))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> Money.krw(new BigDecimal("1.0000001"))).isInstanceOf(ApiException.class);
        assertThat(Money.parse("rate", "1400.1234567891", Money.RATE_SCALE, 14)).isEqualByComparingTo("1400.1234567891");
    }

    @Test
    void rejectsNonDecimalStrings() {
        for (String value : new String[] {"1e3", "01", "1.", ".5", "abc", "1,000"}) {
            assertThatThrownBy(() -> Money.parse("amount", value, 6, 18)).as(value).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void scalesStoredValuesExactly() {
        assertThat(Money.krw(new BigDecimal("20000")).toPlainString()).isEqualTo("20000.000000");
        assertThat(Money.original(new BigDecimal("0.1")).scale()).isEqualTo(12);
    }

    @Test
    void currencyCodeMustBeUppercaseIso() {
        assertThatThrownBy(() -> Money.currency("currency", "usd")).isInstanceOf(ApiException.class);
        assertThat(Money.currency("currency", "USD")).isEqualTo("USD");
    }
}
