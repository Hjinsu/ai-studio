package com.aistudio.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aistudio.content.command.CanonicalJson;

/** revision_hash v1 canonical 규칙(DM-04). */
class RevisionHashTest {

    private static RevisionHash.Input input(Map<String, Object> settings, String title) {
        return new RevisionHash.Input(7, 3, null, title, "brief\nline", "en", 45, settings);
    }

    @Test
    void hashMatchesTheDocumentedCanonicalJson() {
        String expectedJson = "{\"baseRunId\":null,\"brief\":\"brief\\nline\",\"channelConfigId\":3,\"contentId\":7,"
                + "\"hashVersion\":1,\"language\":\"en\",\"settings\":null,\"targetDurationSeconds\":45,"
                + "\"workingTitle\":\"Title\"}";
        assertThat(RevisionHash.compute(input(null, "Title"))).isEqualTo(CanonicalJson.sha256Hex(expectedJson));
    }

    @Test
    void settingsKeyOrderDoesNotChangeTheHashButValuesDo() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("voice", "calm");
        a.put("music", Map.of("volume", 3, "genre", "lofi"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("music", Map.of("genre", "lofi", "volume", 3));
        b.put("voice", "calm");
        assertThat(RevisionHash.compute(input(a, "Title"))).isEqualTo(RevisionHash.compute(input(b, "Title")));
        assertThat(RevisionHash.compute(input(a, "Title"))).isNotEqualTo(RevisionHash.compute(input(a, "Title2")));
        assertThat(RevisionHash.compute(input(a, "Title"))).isNotEqualTo(RevisionHash.compute(input(null, "Title")));
    }

    @Test
    void ownershipFieldsArePartOfTheHash() {
        String base = RevisionHash.compute(input(null, "Title"));
        assertThat(RevisionHash.compute(new RevisionHash.Input(7, 4, null, "Title", "brief\nline", "en", 45, null)))
                .isNotEqualTo(base);
        assertThat(RevisionHash.compute(new RevisionHash.Input(8, 3, null, "Title", "brief\nline", "en", 45, null)))
                .isNotEqualTo(base);
        assertThat(RevisionHash.compute(new RevisionHash.Input(7, 3, 9L, "Title", "brief\nline", "en", 45, null)))
                .isNotEqualTo(base);
    }
}
