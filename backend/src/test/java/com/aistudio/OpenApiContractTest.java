package com.aistudio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
// 검증 라이브러리(swagger-parser)가 쓰는 Jackson 2 트리. 애플리케이션의 Jackson 3(tools.jackson)과 별개
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * OpenAPI 계약 검증기 자체의 동작 검증(P-T02-01). ApiTestSupport는 원본 명세를 변형 없이 쓰므로, 여기서
 * (1) 평탄화 명세 규칙과 (2) 정상 허용·미정의 필드 거부·필수 누락 거부·자료형 오류 거부를 확인한다.
 * 검증기가 무조건 통과하거나 정상 응답을 거부하면 이 테스트가 실패한다.
 */
class OpenApiContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = ApiTestSupport.validator();

    private static final String SETTINGS = "\"defaultContentType\":\"LONGFORM\",\"shortsMinSeconds\":40,"
            + "\"shortsMaxSeconds\":60,\"longformMinSeconds\":480,\"longformMaxSeconds\":720,"
            + "\"contentStrategy\":null,\"targetAudience\":null,\"narrationStyle\":null,\"visualStyle\":null,"
            + "\"syntheticDisclosureDefault\":true,\"providerOptions\":{\"anyKey\":\"allowed\"}";
    private static final String CONFIG_META = "\"channelId\":\"2\",\"revisionNo\":2,\"changeNote\":\"x\","
            + "\"createdBy\":\"1\",\"createdAt\":\"2026-11-25T00:00:00Z\"";
    private static final String CHANNEL = "{\"id\":\"1\",\"name\":\"a\",\"language\":\"en\",\"timezone\":\"UTC\","
            + "\"status\":\"ACTIVE\",\"version\":0,\"currentConfigRevisionNo\":1,"
            + "\"createdAt\":\"2026-11-25T00:00:00Z\",\"updatedAt\":\"2026-11-25T00:00:00Z\",\"archivedAt\":null}";
    private static final String PAGE = "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1";

    private static ValidationReport configResponse(String body) {
        return VALIDATOR.validateResponse("/api/v1/channels/2/config/revisions", Request.Method.POST,
                SimpleResponse.Builder.status(201).withContentType("application/json").withBody(body).build());
    }

    private static ValidationReport channelPage(String body) {
        return VALIDATOR.validateResponse("/api/v1/channels", Request.Method.GET,
                SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build());
    }

    private static ValidationReport configRequest(String body) {
        return VALIDATOR.validateRequest(SimpleRequest.Builder.post("/api/v1/channels/2/config/revisions")
                .withContentType("application/json").withBody(body).build());
    }

    // ---------------------------------------------------------------- 공용 설정 + 메타(ChannelConfig)
    @Test
    void channelConfigResponseAcceptsValidAndRejectsViolations() {
        assertThat(configResponse("{" + SETTINGS + "," + CONFIG_META + "}").getMessages()).isEmpty();
        // 미정의 필드(최상위)
        assertThat(configResponse("{" + SETTINGS + "," + CONFIG_META + ",\"secret\":1}").hasErrors()).isTrue();
        // 필수 누락: 메타(channelId) / 설정(shortsMinSeconds)
        assertThat(configResponse("{" + SETTINGS + "," + CONFIG_META.replace("\"channelId\":\"2\",", "") + "}")
                .hasErrors()).isTrue();
        assertThat(configResponse("{" + SETTINGS.replace("\"shortsMinSeconds\":40,", "") + "," + CONFIG_META + "}")
                .hasErrors()).isTrue();
        // 자료형 오류: 메타(revisionNo) / 설정(syntheticDisclosureDefault)
        assertThat(configResponse("{" + SETTINGS + "," + CONFIG_META.replace("\"revisionNo\":2", "\"revisionNo\":\"2\"")
                + "}").hasErrors()).isTrue();
        assertThat(configResponse("{" + SETTINGS.replace("\"syntheticDisclosureDefault\":true",
                "\"syntheticDisclosureDefault\":\"yes\"") + "," + CONFIG_META + "}").hasErrors()).isTrue();
    }

    @Test
    void channelConfigRequestSettingsAreStrict() {
        assertThat(configRequest("{\"baseRevisionNo\":1,\"settings\":{" + SETTINGS + "}}").getMessages()).isEmpty();
        assertThat(configRequest("{\"baseRevisionNo\":1,\"settings\":{" + SETTINGS + ",\"channelId\":\"2\"}}")
                .hasErrors()).isTrue();
        assertThat(configRequest("{\"baseRevisionNo\":1,\"settings\":{"
                + SETTINGS.replace("\"defaultContentType\":\"LONGFORM\",", "") + "}}").hasErrors()).isTrue();
        assertThat(configRequest("{\"baseRevisionNo\":1,\"settings\":{"
                + SETTINGS.replace("\"shortsMaxSeconds\":60", "\"shortsMaxSeconds\":\"60\"") + "}}").hasErrors())
                .isTrue();
        assertThat(configRequest("{\"baseRevisionNo\":1,\"settings\":{" + SETTINGS + "},\"extra\":true}").hasErrors())
                .isTrue();
    }

    // ---------------------------------------------------------------- PageMeta + items(페이지 응답)
    @Test
    void pageResponseAcceptsValidAndRejectsViolations() {
        assertThat(channelPage("{" + PAGE + ",\"items\":[" + CHANNEL + "]}").getMessages()).isEmpty();
        assertThat(channelPage("{" + PAGE + ",\"items\":[],\"extra\":1}").hasErrors()).isTrue();
        // 항목 안의 미정의 필드(검증기 주입 additionalProperties=false가 하위 스키마에서 유지되는지)
        assertThat(channelPage("{" + PAGE + ",\"items\":[" + CHANNEL.replace("{\"id\"", "{\"x\":1,\"id\"") + "]}")
                .hasErrors()).isTrue();
        assertThat(channelPage("{" + PAGE.replace(",\"totalPages\":1", "") + ",\"items\":[]}").hasErrors()).isTrue();
        assertThat(channelPage("{" + PAGE + "}").hasErrors()).isTrue();
        assertThat(channelPage("{" + PAGE.replace("\"page\":0", "\"page\":\"0\"") + ",\"items\":[]}").hasErrors())
                .isTrue();
    }

    // ---------------------------------------------------------------- 명세 규칙
    /**
     * P-T02-01: 검증 라이브러리는 allOf 조합 안의 스키마마다 additionalProperties=false를 주입하고, 조합에
     * unevaluatedProperties를 두면 중첩 스키마(items 등)에 주입하지 않아 미정의 필드를 놓친다(실측). 따라서 명세는
     * allOf/anyOf 조합을 쓰지 않고 최종 스키마를 평탄화한다. 평탄화된 스키마의 공통 필드는 기반 스키마와 같아야 한다.
     */
    @Test
    void schemasAreFlatAndConsistentWithTheirBases() throws IOException {
        JsonNode root = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(Path.of("..", "docs", "api", "openapi.yaml")));
        List<String> violations = new ArrayList<>();
        walk(root, "", violations);
        assertThat(violations).isEmpty();

        JsonNode schemas = root.path("components").path("schemas");
        List<String> pages = new ArrayList<>();
        schemas.fieldNames().forEachRemaining(name -> {
            if (name.endsWith("Page")) {
                pages.add(name);
            }
        });
        assertThat(pages).hasSizeGreaterThanOrEqualTo(8);
        for (String page : pages) {
            assertContainsBase(schemas, page, "PageMeta");
            assertThat(schemas.path(page).path("required").toString()).as(page).contains("\"items\"");
        }
        assertContainsBase(schemas, "ChannelConfig", "ChannelConfigSettings");
        assertContainsBase(schemas, "ReservationDetail", "Reservation");
    }

    private static void assertContainsBase(JsonNode schemas, String name, String base) {
        JsonNode schema = schemas.path(name);
        JsonNode baseSchema = schemas.path(base);
        baseSchema.path("properties").fields().forEachRemaining(entry -> assertThat(
                schema.path("properties").path(entry.getKey())).as(name + "." + entry.getKey() + " == " + base)
                .isEqualTo(entry.getValue()));
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        baseSchema.path("required").forEach(n -> assertThat(required).as(name + " requires " + n.asText())
                .contains(n.asText()));
    }

    private static void walk(JsonNode node, String path, List<String> violations) {
        if (node.isObject() && (node.has("allOf") || node.has("anyOf"))) {
            violations.add(path + ": allOf/anyOf composition (flatten the schema, P-T02-01)");
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            walk(entry.getValue(), path + "/" + entry.getKey(), violations);
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "/" + i, violations);
            }
        }
    }
}
