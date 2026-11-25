package com.aistudio.topic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aistudio.content.command.CanonicalJson;

/** Topic 정규화 v1 (NFKC → 소문자 → 공백 압축 → trim)와 hash 규칙. */
class TopicNormalizerTest {

    @Test
    void caseFullWidthAndWhitespaceDifferencesNormalizeToTheSameTitle() {
        String base = TopicNormalizer.normalize("Space Travel 101");
        assertThat(base).isEqualTo("space travel 101");
        assertThat(TopicNormalizer.normalize("  SPACE\t\ttravel\n101  ")).isEqualTo(base);
        // 전각 영문·숫자·공백(U+3000)은 NFKC로 반각이 된다
        assertThat(TopicNormalizer.normalize("Ｓｐａｃｅ　Ｔｒａｖｅｌ　１０１")).isEqualTo(base);
        // 비분리 공백(U+00A0)도 공백으로 압축된다
        assertThat(TopicNormalizer.normalize("space travel 101")).isEqualTo(base);
        assertThat(TopicNormalizer.normalize("Space Travel 102")).isNotEqualTo(base);
    }

    @Test
    void localeIndependentLowercase() {
        // Locale.ROOT: 터키어 로캘에서도 I → i
        assertThat(TopicNormalizer.normalize("TITLE I")).isEqualTo("title i");
        assertThat(TopicNormalizer.normalize("우주 여행")).isEqualTo("우주 여행");
    }

    @Test
    void hashIsSha256OfVersionConcatenatedWithNormalizedTitle() {
        String normalized = TopicNormalizer.normalize("Space Travel");
        String hash = TopicNormalizer.hash(TopicNormalizer.VERSION, normalized);
        assertThat(hash).matches("^[0-9a-f]{64}$").isEqualTo(CanonicalJson.sha256Hex("1space travel"));
        assertThat(TopicNormalizer.hash(TopicNormalizer.VERSION, TopicNormalizer.normalize("SPACE  travel")))
                .isEqualTo(hash);
        assertThat(TopicNormalizer.hash(2, normalized)).isNotEqualTo(hash);
    }
}
