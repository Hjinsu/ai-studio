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
    void hashIsSha256OfTheCanonicalVersionTitleArray() {
        String normalized = TopicNormalizer.normalize("Space Travel");
        String hash = TopicNormalizer.hash(TopicNormalizer.VERSION, normalized);
        assertThat(TopicNormalizer.preimage(1, normalized)).isEqualTo("[1,\"space travel\"]");
        assertThat(hash).matches("^[0-9a-f]{64}$").isEqualTo(CanonicalJson.sha256Hex("[1,\"space travel\"]"));
        assertThat(TopicNormalizer.hash(TopicNormalizer.VERSION, TopicNormalizer.normalize("SPACE  travel")))
                .isEqualTo(hash);
        assertThat(TopicNormalizer.hash(2, normalized)).isNotEqualTo(hash);
    }

    @Test
    void versionAndTitleBoundaryCannotCollide() {
        // RV-B-03: 구분자 없는 연결이면 (1, "2topic")과 (12, "topic")이 모두 "12topic"이 된다
        assertThat(TopicNormalizer.preimage(1, "2topic")).isEqualTo("[1,\"2topic\"]");
        assertThat(TopicNormalizer.preimage(12, "topic")).isEqualTo("[12,\"topic\"]");
        assertThat(TopicNormalizer.hash(1, "2topic")).isNotEqualTo(TopicNormalizer.hash(12, "topic"));
        // 따옴표·쉼표·대괄호가 든 제목도 JSON 이스케이프로 경계가 유지된다
        assertThat(TopicNormalizer.preimage(1, "a\",2,\"b")).isEqualTo("[1,\"a\\\",2,\\\"b\"]");
        assertThat(TopicNormalizer.hash(1, "a\",2,\"b")).isNotEqualTo(TopicNormalizer.hash(2, "b"));
        for (int v = 1; v <= 20; v++) {
            for (int w = 1; w <= 20; w++) {
                if (v != w) {
                    assertThat(TopicNormalizer.preimage(v, w + "x")).isNotEqualTo(TopicNormalizer.preimage(w, v + "x"));
                }
            }
        }
    }
}
