package com.aistudio.topic;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import com.aistudio.content.command.CanonicalJson;

/**
 * Topic 제목 정규화 v1 (T03 DB 명세 topic.normalized_title/normalized_hash).
 * NFKC → 소문자(Locale.ROOT) → 연속 공백 1칸 압축 → trim. hash = SHA-256(UTF-8(normalization_version || normalized_title)).
 */
public final class TopicNormalizer {

    public static final int VERSION = 1;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private TopicNormalizer() {
    }

    public static String normalize(String title) {
        String nfkc = Normalizer.normalize(title, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(nfkc.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    public static String hash(int version, String normalizedTitle) {
        return CanonicalJson.sha256Hex(version + normalizedTitle);
    }
}
