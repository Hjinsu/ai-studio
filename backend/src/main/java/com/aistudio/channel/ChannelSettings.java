package com.aistudio.channel;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern.Flag;
import jakarta.validation.constraints.Size;

import com.aistudio.shared.api.ApiException;

/**
 * 채널 핵심 설정(정규화 컬럼)과 provider 옵션(JSON) (ADR-034, ADR-039). 합성 고지 기본값은 UI 초기값일 뿐이다.
 */
public record ChannelSettings(
        @NotNull @jakarta.validation.constraints.Pattern(regexp = "SHORTS|LONGFORM", flags = Flag.CASE_INSENSITIVE)
        String defaultContentType,
        @NotNull @Min(1) @Max(180) Integer shortsMinSeconds,
        @NotNull @Min(1) @Max(180) Integer shortsMaxSeconds,
        @NotNull @Min(60) @Max(14400) Integer longformMinSeconds,
        @NotNull @Min(60) @Max(14400) Integer longformMaxSeconds,
        @Size(max = 2000) String contentStrategy,
        @Size(max = 500) String targetAudience,
        @Size(max = 500) String narrationStyle,
        @Size(max = 500) String visualStyle,
        @NotNull Boolean syntheticDisclosureDefault,
        Map<String, Object> providerOptions) {

    /** 비밀값이 들어갈 만한 키는 거부한다(provider 옵션에 비밀 저장 금지). */
    private static final Pattern SECRET_KEY = Pattern.compile(
            ".*(secret|token|password|passwd|api[_-]?key|credential|private[_-]?key).*", Pattern.CASE_INSENSITIVE);

    public static ChannelSettings defaults() {
        return new ChannelSettings("SHORTS", 40, 60, 480, 720, null, null, null, null, true, null);
    }

    String normalizedContentType() {
        return defaultContentType.toUpperCase(Locale.ROOT);
    }

    void validate() {
        if (shortsMinSeconds > shortsMaxSeconds) {
            throw ApiException.validation("shortsMinSeconds", "must be less than or equal to shortsMaxSeconds");
        }
        if (longformMinSeconds > longformMaxSeconds) {
            throw ApiException.validation("longformMinSeconds", "must be less than or equal to longformMaxSeconds");
        }
        if (providerOptions != null) {
            rejectSecretKeys(providerOptions, "providerOptions");
        }
    }

    private static void rejectSecretKeys(Map<?, ?> map, String path) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (SECRET_KEY.matcher(key).matches()) {
                throw ApiException.validation(path + "." + key, "secret values must not be stored in provider options");
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                rejectSecretKeys(nested, path + "." + key);
            }
        }
    }
}
