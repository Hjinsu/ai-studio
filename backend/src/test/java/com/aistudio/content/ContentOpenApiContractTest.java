package com.aistudio.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aistudio.ApiTestSupport;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

/** Verifies that the product OpenAPI validator rejects invalid B requests and responses. */
class ContentOpenApiContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = ApiTestSupport.validator();
    private static final String TOPIC = "{\"id\":\"1\",\"channelId\":\"2\",\"title\":\"t\",\"note\":null,"
            + "\"createdAt\":\"2026-11-25T00:00:00Z\"}";
    private static final String REVISION = "{\"id\":\"3\",\"contentId\":\"4\",\"revisionNo\":1,\"workingTitle\":\"w\","
            + "\"brief\":\"b\",\"language\":\"en\",\"targetDurationSeconds\":50,\"settings\":{},\"baseRunId\":null,"
            + "\"revisionHash\":\"" + "a".repeat(64) + "\",\"createdAt\":\"2026-11-25T00:00:00Z\"}";
    private static final String CONTENT = "{\"id\":\"4\",\"channelId\":\"2\",\"topicId\":\"1\",\"contentType\":\"SHORTS\","
            + "\"title\":\"w\",\"status\":\"DRAFT\",\"version\":0,\"currentRevision\":" + REVISION
            + ",\"activeRunId\":null}";

    private static ValidationReport topic(int status, String body) {
        return VALIDATOR.validateResponse("/api/v1/topics/1", Request.Method.GET,
                SimpleResponse.Builder.status(status).withContentType("application/json").withBody(body).build());
    }

    private static ValidationReport content(String body) {
        return VALIDATOR.validateResponse("/api/v1/contents/4", Request.Method.GET,
                SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build());
    }

    @Test
    void topicResponseIsStrict() {
        assertThat(topic(200, TOPIC).getMessages()).isEmpty();
        assertThat(topic(200, TOPIC.replace("{\"id\"", "{\"extra\":1,\"id\"")).hasErrors()).isTrue();
        assertThat(topic(200, TOPIC.replace(",\"createdAt\":\"2026-11-25T00:00:00Z\"", "")).hasErrors()).isTrue();
        assertThat(topic(200, TOPIC.replace("\"id\":\"1\"", "\"id\":1")).hasErrors()).isTrue();
        assertThat(topic(404, "{\"code\":\"RESOURCE_NOT_FOUND\"}").hasErrors()).isTrue();
    }

    @Test
    void contentResponseIsStrictExceptTheDocumentedBudgetSummaryGap() {
        assertThat(content(CONTENT).getMessages()).isEmpty();
        assertThat(content(CONTENT.replace("\"status\":\"DRAFT\"", "\"status\":\"DONE\"")).hasErrors()).isTrue();
        assertThat(content(CONTENT.replace("\"settings\":{}", "\"settings\":null")).hasErrors()).isTrue();
        assertThat(content(CONTENT.replace("\"activeRunId\":null", "\"activeRunId\":null,\"x\":1")).hasErrors())
                .isTrue();
        assertThat(content(CONTENT.replace("\"revisionHash\":\"a", "\"revisionHash\":\"A")).hasErrors()).isTrue();
    }

    @Test
    void requestsRequireTheIdempotencyKeyAndStrictBodies() {
        String body = "{\"channelId\":\"2\",\"title\":\"t\"}";
        assertThat(VALIDATOR.validateRequest(SimpleRequest.Builder.post("/api/v1/topics")
                .withHeader("Idempotency-Key", "valid-key-1").withContentType("application/json").withBody(body)
                .build()).getMessages()).isEmpty();
        assertThat(VALIDATOR.validateRequest(SimpleRequest.Builder.post("/api/v1/topics")
                .withContentType("application/json").withBody(body).build()).hasErrors()).isTrue();
        assertThat(VALIDATOR.validateRequest(SimpleRequest.Builder.post("/api/v1/topics")
                .withHeader("Idempotency-Key", "valid-key-1").withContentType("application/json")
                .withBody("{\"channelId\":\"2\",\"title\":\"t\",\"normalizedTitle\":\"t\"}").build()).hasErrors())
                .isTrue();
    }
}
