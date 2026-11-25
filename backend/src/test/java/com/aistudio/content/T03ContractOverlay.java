package com.aistudio.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.test.web.servlet.ResultMatcher;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
// 검증 라이브러리(swagger-parser)의 Jackson 2 트리. 애플리케이션의 Jackson 3과 별개
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * FV-05: 제품 계약(docs/api/openapi.yaml)에 T03 검토 초안(docs/plans/t03-final/T03_OPENAPI_DRAFT.yaml)의 B 범위
 * 경로·스키마를 합친 메모리 사본으로 요청·응답을 검증한다. 두 파일은 수정하지 않는다(OpenAPI 통합은 Codex 소관).
 * <p>
 * 의도적 편차 1건: Content.budgetSummary는 T03-D(비용) 소유 필드라 B 응답에 없다. 사본에서만 required에서 빼며,
 * 보고서의 계약 반영 요청으로 전달한다.
 */
final class T03ContractOverlay {

    static final Set<String> B_PATHS = Set.of("/api/v1/topics", "/api/v1/topics/{topicId}", "/api/v1/contents",
            "/api/v1/contents/{contentId}", "/api/v1/contents/{contentId}/archive",
            "/api/v1/contents/{contentId}/revisions", "/api/v1/contents/{contentId}/revisions/{revisionNo}");
    private static final String PRODUCT_REF = "../../api/openapi.yaml#/components/schemas/";
    private static final String DRAFT_PREFIX = "T03";

    private static OpenApiInteractionValidator validator;

    private T03ContractOverlay() {
    }

    static ResultMatcher matchesContract() {
        return OpenApiValidationMatchers.openApi().isValid(validator());
    }

    static synchronized OpenApiInteractionValidator validator() {
        if (validator == null) {
            validator = OpenApiInteractionValidator.createForInlineApiSpecification(merged().toString())
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request.security", ValidationReport.Level.IGNORE)
                            .build())
                    .build();
        }
        return validator;
    }

    static ObjectNode merged() {
        try {
            ObjectNode product = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(
                    Files.readString(Path.of("..", "docs", "api", "openapi.yaml"), StandardCharsets.UTF_8));
            ObjectNode draft = (ObjectNode) new ObjectMapper().readTree(Files.readString(
                    Path.of("..", "docs", "plans", "t03-final", "T03_OPENAPI_DRAFT.yaml"), StandardCharsets.UTF_8));
            ObjectNode productSchemas = (ObjectNode) product.path("components").path("schemas");
            ObjectNode draftSchemas = (ObjectNode) draft.path("components").path("schemas");
            // 초안 스키마는 이름 충돌을 피하려고 접두사를 붙여 제품 components에 넣는다
            List<String> names = new ArrayList<>();
            draftSchemas.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                productSchemas.set(DRAFT_PREFIX + name, rewrite(draftSchemas.get(name).deepCopy()));
            }
            ObjectNode content = (ObjectNode) productSchemas.get(DRAFT_PREFIX + "Content");
            ArrayNode required = (ArrayNode) content.get("required");
            for (Iterator<JsonNode> it = required.iterator(); it.hasNext();) {
                if (it.next().asText().equals("budgetSummary")) {
                    it.remove();
                }
            }
            ObjectNode paths = (ObjectNode) product.path("paths");
            for (Map.Entry<String, JsonNode> entry : List.copyOf(draft.path("paths").properties())) {
                if (B_PATHS.contains(entry.getKey())) {
                    if (paths.has(entry.getKey())) {
                        throw new IllegalStateException("제품 계약에 이미 있는 경로: " + entry.getKey());
                    }
                    paths.set(entry.getKey(), rewrite(entry.getValue().deepCopy()));
                }
            }
            return product;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode rewrite(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual()) {
                String value = ref.asText();
                if (value.startsWith(PRODUCT_REF)) {
                    object.put("$ref", "#/components/schemas/" + value.substring(PRODUCT_REF.length()));
                } else if (value.startsWith("#/components/schemas/")) {
                    object.put("$ref", "#/components/schemas/" + DRAFT_PREFIX
                            + value.substring("#/components/schemas/".length()));
                } else {
                    throw new IllegalStateException("지원하지 않는 참조: " + value);
                }
            }
            for (Map.Entry<String, JsonNode> field : List.copyOf(object.properties())) {
                rewrite(field.getValue());
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(T03ContractOverlay::rewrite);
        }
        return node;
    }
}
