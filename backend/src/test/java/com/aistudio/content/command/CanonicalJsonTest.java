package com.aistudio.content.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void keysAreSortedRecursivelyWithExplicitNullsAndNoWhitespace() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("a", List.of(true, "x"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", nested);
        value.put("a", null);
        assertThat(CanonicalJson.write(value)).isEqualTo("{\"a\":null,\"b\":{\"a\":[true,\"x\"],\"z\":1}}");
    }

    @Test
    void insertionOrderDoesNotChangeTheDigest() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("x", 1L);
        first.put("y", Map.of("q", 2, "p", 3));
        Map<String, Object> second = new HashMap<>();
        second.put("y", Map.of("p", 3, "q", 2));
        second.put("x", 1);
        assertThat(CanonicalJson.sha256(first)).isEqualTo(CanonicalJson.sha256(second)).matches("^[0-9a-f]{64}$");
    }

    @Test
    void keysUseCodePointOrderNotUtf16Order() {
        // U+FF5E(BMP 끝쪽)와 U+1F600(보조 평면): UTF-16 순서는 반대지만 코드포인트 순으로 정렬한다
        String bmp = "～";
        String supplementary = new String(Character.toChars(0x1F600));
        assertThat(bmp.compareTo(supplementary)).isPositive();
        String json = CanonicalJson.write(Map.of(supplementary, 2, bmp, 1));
        assertThat(json.indexOf(bmp)).isLessThan(json.indexOf(supplementary));
    }

    @Test
    void stringsAreNotNormalized() {
        assertThat(CanonicalJson.write(Map.of("k", " A\nb "))).isEqualTo("{\"k\":\" A\\nb \"}");
    }

    @Test
    void nonIntegerNumbersAndUnsupportedValuesAreRejected() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("ratio", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("x", new java.math.BigDecimal("1.0"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Arrays.asList(new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
