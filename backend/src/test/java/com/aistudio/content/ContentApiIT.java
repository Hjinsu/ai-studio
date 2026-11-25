package com.aistudio.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;
import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Topic·Content·Revision API (T03-B, FV-05): ADMIN 세션·CSRF, 문자열 ID, UTC, 공통 오류, Idempotency-Key 재생과
 * 다른 본문 409, baseRevisionNo, 소유권·상태 전이, FD-16 채널 보관 차단. 모든 응답을 제품 계약 + T03 초안 사본으로 검증한다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentApiIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private ChannelService channels;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private AuditService audit;

    private AdminPrincipal admin;
    private ContentFixtures fx;

    @BeforeEach
    void setUp() {
        admin = ApiTestSupport.admin(accounts, jdbc);
        fx = new ContentFixtures(jdbc);
    }

    // ---------------------------------------------------------------- helpers
    private long channel() {
        return channels.create("api-" + UUID.randomUUID(), "en", "UTC", null, Actor.admin(admin.id())).channel().id();
    }

    private static String key() {
        return ApiTestSupport.uniqueKey("it");
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(ApiTestSupport.as(admin))).andExpect(T03ContractOverlay.matchesContract());
    }

    private ResultActions postJson(String path, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return send(request);
    }

    /** 의도적으로 계약 위반 요청을 보낼 때: 요청 검증은 생략하고 응답(공통 오류 스키마)만 계약으로 검증한다. */
    private ResultActions invalid(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mockMvc.perform(request.with(ApiTestSupport.as(admin)));
        MvcResult mvc = result.andReturn();
        var response = SimpleResponse.Builder.status(mvc.getResponse().getStatus())
                .withContentType(mvc.getResponse().getContentType())
                .withBody(mvc.getResponse().getContentAsString()).build();
        ValidationReport report = T03ContractOverlay.validator().validateResponse(mvc.getRequest().getRequestURI(),
                Request.Method.valueOf(mvc.getRequest().getMethod()), response);
        assertThat(report.getMessages()).as(mvc.getRequest().getRequestURI()).isEmpty();
        return result;
    }

    private ResultActions postInvalid(String path, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return invalid(request);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String topicBody(long channelId, String title) {
        return "{\"channelId\":\"" + channelId + "\",\"title\":\"" + title + "\"}";
    }

    private JsonNode createTopic(long channelId, String title) throws Exception {
        return json(postJson("/api/v1/topics", key(), topicBody(channelId, title)).andExpect(status().isCreated()));
    }

    private static String contentBody(long channelId, String topicId, String extra) {
        return "{\"channelId\":\"" + channelId + "\",\"topicId\":\"" + topicId + "\",\"contentType\":\"SHORTS\","
                + "\"workingTitle\":\"Working Title\",\"brief\":\"Explain the topic.\",\"targetDurationSeconds\":50"
                + extra + "}";
    }

    private JsonNode createContent(long channelId) throws Exception {
        String topicId = createTopic(channelId, "Topic " + UUID.randomUUID()).get("id").asString();
        return json(postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ""))
                .andExpect(status().isCreated()));
    }

    private static String revisionBody(int baseRevisionNo, String title, String extra) {
        return "{\"baseRevisionNo\":" + baseRevisionNo + ",\"workingTitle\":\"" + title + "\",\"brief\":\"Brief\","
                + "\"language\":\"en\",\"targetDurationSeconds\":45" + extra + "}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ---------------------------------------------------------------- 인증·CSRF·공통
    @Test
    void writeEndpointsRequireAdminSessionAndCsrf() throws Exception {
        long channelId = channel();
        mockMvc.perform(get("/api/v1/contents")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/topics").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(topicBody(channelId, "csrf"))).andExpect(status().isForbidden());
        // 인증은 있으나 CSRF 토큰 없음
        var auth = authentication(UsernamePasswordAuthenticationToken.authenticated(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        mockMvc.perform(post("/api/v1/topics").with(auth).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(topicBody(channelId, "csrf")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channelId))
                .isZero();
        // Idempotency-Key 누락·형식 오류
        postInvalid("/api/v1/topics", null, topicBody(channelId, "nokey")).andExpect(status().isBadRequest());
        postInvalid("/api/v1/topics", "bad key!", topicBody(channelId, "badkey")).andExpect(status().isBadRequest());
        invalid(get("/api/v1/topics/0")).andExpect(status().isBadRequest());
        send(get("/api/v1/contents/999999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- Topic
    @Test
    void topicIsIdempotentAndDuplicatesAreBlockedPerChannel() throws Exception {
        long channelId = channel();
        String k = key();
        JsonNode created = json(postJson("/api/v1/topics", k, topicBody(channelId, "Space  Travel"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")));
        String topicId = created.get("id").asString();
        assertThat(created.get("channelId").asString()).isEqualTo(String.valueOf(channelId));
        assertThat(created.get("title").asString()).isEqualTo("Space  Travel");
        assertThat(created.get("createdAt").asString()).endsWith("Z");
        // 같은 키·같은 본문 재생 → 200, 같은 ID
        postJson("/api/v1/topics", k, topicBody(channelId, "Space  Travel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(topicId));
        // 같은 키·다른 본문 → 409 RESOURCE_CONFLICT
        postJson("/api/v1/topics", k, topicBody(channelId, "Other")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        // 정규화 중복(대소문자·전각·공백) → 409 DUPLICATE_RESOURCE + 기존 ID
        postJson("/api/v1/topics", key(), topicBody(channelId, "ＳＰＡＣＥ travel ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.details[0].field").value("title"))
                .andExpect(jsonPath("$.details[0].reason").value("DUPLICATE_TOPIC:" + topicId));
        // 다른 채널의 같은 소재는 허용
        long otherChannel = channel();
        createTopic(otherChannel, "Space Travel");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE channel_id = ?", Integer.class, channelId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM queue_command_log WHERE request_key = ?", Integer.class, k))
                .isEqualTo(1);

        send(get("/api/v1/topics/" + topicId)).andExpect(status().isOk()).andExpect(jsonPath("$.note").isEmpty());
        send(get("/api/v1/topics").param("channelId", String.valueOf(channelId)).param("q", "SPACE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(topicId));
        send(get("/api/v1/topics").param("channelId", String.valueOf(channelId)).param("q", "%"))
                .andExpect(jsonPath("$.totalElements").value(0));
        send(get("/api/v1/topics").param("channelId", "999999999")).andExpect(status().isNotFound());
        invalid(get("/api/v1/topics")).andExpect(status().isBadRequest());
        postJson("/api/v1/topics", key(), topicBody(999999999L, "x")).andExpect(status().isNotFound());
    }

    @Test
    void archivedChannelRejectsNewTopicsAndContents() throws Exception {
        long channelId = channel();
        String topicId = createTopic(channelId, "Before archive").get("id").asString();
        channels.archive(channelId, channels.get(channelId).channel().version(), Actor.admin(admin.id()));
        postJson("/api/v1/topics", key(), topicBody(channelId, "After archive")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_MET"));
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_MET"));
    }

    // ---------------------------------------------------------------- Content
    @Test
    void contentCreationLinksTheFirstRevisionAtomicallyAndReplays() throws Exception {
        long channelId = channel();
        String topicId = createTopic(channelId, "Content topic").get("id").asString();
        String k = key();
        String body = contentBody(channelId, topicId, ",\"settings\":{\"voice\":\"calm\",\"music\":{\"volume\":3}}");
        JsonNode created = json(postJson("/api/v1/contents", k, body).andExpect(status().isCreated()));
        String contentId = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("DRAFT");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("activeRunId").isNull()).isTrue();
        assertThat(created.get("title").asString()).isEqualTo("Working Title");
        JsonNode revision = created.get("currentRevision");
        assertThat(revision.get("revisionNo").asInt()).isEqualTo(1);
        assertThat(revision.get("language").asString()).isEqualTo("en"); // 채널 기본 언어
        assertThat(revision.get("settings").get("music").get("volume").asInt()).isEqualTo(3);
        assertThat(revision.get("revisionHash").asString()).matches("^[0-9a-f]{64}$");
        // DB: 포인터·제목 복제본·고정 설정이 일치
        var row = jdbc.queryForMap("""
                SELECT c.title, r.working_title, r.channel_config_id, cc.revision_no AS config_no
                  FROM content c JOIN content_revision r ON r.id = c.current_revision_id
                  JOIN channel_config cc ON cc.id = r.channel_config_id WHERE c.id = ?""", Long.parseLong(contentId));
        assertThat(row.get("title")).isEqualTo(row.get("working_title"));
        assertThat(((Number) row.get("config_no")).intValue()).isEqualTo(1);

        int contents = count("content");
        int revisions = count("content_revision");
        postJson("/api/v1/contents", k, body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(contentId));
        postJson("/api/v1/contents", k, body.replace("Working Title", "Changed")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        // 다른 명령 유형에 같은 키 → 409(전역 키 공간)
        postJson("/api/v1/topics", k, topicBody(channelId, "Reuse key")).andExpect(status().isConflict());
        assertThat(count("content")).isEqualTo(contents);
        assertThat(count("content_revision")).isEqualTo(revisions);

        send(get("/api/v1/contents/" + contentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision.id").value(revision.get("id").asString()));
    }

    @Test
    void contentCreationRollsBackEverythingOnMidTransactionFailure() throws Exception {
        long channelId = channel();
        String topicId = createTopic(channelId, "Failure topic").get("id").asString();
        int contents = count("content");
        int revisions = count("content_revision");
        int logs = count("queue_command_log");
        String k = key();
        // content·revision INSERT와 포인터 설정 뒤, 명령 로그 INSERT 전에 실패를 주입한다
        doThrow(new IllegalStateException("injected")).when(audit)
                .record(any(), eq("CONTENT_CREATE"), anyString(), any(), any());
        try {
            // 500은 계약 정의 밖(주입 장애)이므로 계약 검증 없이 상태만 확인한다
            mockMvc.perform(post("/api/v1/contents").with(ApiTestSupport.as(admin)).header("Idempotency-Key", k)
                    .contentType(MediaType.APPLICATION_JSON).content(contentBody(channelId, topicId, "")))
                    .andExpect(status().isInternalServerError());
        } finally {
            reset(audit);
        }
        assertThat(count("content")).isEqualTo(contents);
        assertThat(count("content_revision")).isEqualTo(revisions);
        assertThat(count("queue_command_log")).isEqualTo(logs);
        // 같은 키로 재시도하면 정상 생성된다(실패한 시도는 키를 소비하지 않음)
        postJson("/api/v1/contents", k, contentBody(channelId, topicId, "")).andExpect(status().isCreated());
    }

    @Test
    void contentOwnershipAndInputAreValidated() throws Exception {
        long channelId = channel();
        long otherChannel = channel();
        String foreignTopic = createTopic(otherChannel, "Foreign").get("id").asString();
        String topicId = createTopic(channelId, "Own").get("id").asString();
        // 다른 채널 topic
        postJson("/api/v1/contents", key(), contentBody(channelId, foreignTopic, "")).andExpect(status().isNotFound());
        // 설정 revision 불일치(최신은 2)
        channels.createConfigRevision(channelId, 1, ChannelSettings.defaults(), "rev2", Actor.admin(admin.id()));
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"channelConfigRevisionNo\":1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        JsonNode pinned = json(postJson("/api/v1/contents", key(),
                contentBody(channelId, topicId, ",\"channelConfigRevisionNo\":2")).andExpect(status().isCreated()));
        Integer configNo = jdbc.queryForObject("""
                SELECT cc.revision_no FROM content_revision r JOIN channel_config cc ON cc.id = r.channel_config_id
                 WHERE r.id = ?""", Integer.class, Long.parseLong(pinned.at("/currentRevision/id").asString()));
        assertThat(configNo).isEqualTo(2);
        // 목표 길이가 설정 유형 범위 밖(SHORTS 기본 최대 60초)
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, "").replace("50", "600"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // settings는 정수만
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"settings\":{\"ratio\":1.5}"))
                .andExpect(status().isBadRequest());
        // 언어 형식
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"language\":\"EN\""))
                .andExpect(status().isBadRequest());
        // 미정의 유형
        postInvalid("/api/v1/contents", key(), contentBody(channelId, topicId, "").replace("SHORTS", "MOVIE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentListFiltersAndPaginates() throws Exception {
        long channelId = channel();
        String topicId = createTopic(channelId, "List topic").get("id").asString();
        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/contents", key(), contentBody(channelId, topicId, "").replace("Working Title", "Item_" + i))
                    .andExpect(status().isCreated());
        }
        JsonNode page = json(send(get("/api/v1/contents").param("channelId", String.valueOf(channelId))
                .param("size", "2")).andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("title").asString()).isEqualTo("Item_2"); // id desc
        send(get("/api/v1/contents").param("channelId", String.valueOf(channelId)).param("q", "item_1"))
                .andExpect(jsonPath("$.totalElements").value(1));
        // LIKE 와일드카드는 이스케이프된다
        send(get("/api/v1/contents").param("channelId", String.valueOf(channelId)).param("q", "Item%"))
                .andExpect(jsonPath("$.totalElements").value(0));
        send(get("/api/v1/contents").param("topicId", topicId).param("contentType", "LONGFORM"))
                .andExpect(jsonPath("$.totalElements").value(0));
        invalid(get("/api/v1/contents").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        invalid(get("/api/v1/contents").param("size", "101")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- Revision
    @Test
    void revisionsAdvanceThePointerAndEnforceBaseRevisionAndChanges() throws Exception {
        JsonNode content = createContent(channel());
        String id = content.get("id").asString();
        String path = "/api/v1/contents/" + id + "/revisions";
        String k = key();
        JsonNode rev2 = json(postJson(path, k, revisionBody(1, "Second", "")).andExpect(status().isCreated()));
        assertThat(rev2.get("revisionNo").asInt()).isEqualTo(2);
        assertThat(rev2.get("baseRunId").isNull()).isTrue();
        assertThat(rev2.get("settings").isEmpty()).isTrue();
        JsonNode detail = json(send(get("/api/v1/contents/" + id)).andExpect(status().isOk()));
        assertThat(detail.get("title").asString()).isEqualTo("Second");
        assertThat(detail.get("version").asLong()).isEqualTo(1);
        assertThat(detail.at("/currentRevision/id").asString()).isEqualTo(rev2.get("id").asString());
        // 재생 / 다른 본문
        postJson(path, k, revisionBody(1, "Second", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rev2.get("id").asString()));
        postJson(path, k, revisionBody(1, "Different", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        // 오래된 baseRevisionNo
        postJson(path, key(), revisionBody(1, "Third", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        // 현재와 같은 입력(줄바꿈 CRLF도 같은 저장값) → NO_CHANGES
        postJson(path, key(), revisionBody(2, "Second", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.details[0].reason").value("NO_CHANGES"));
        // settings 키 순서만 다른 경우도 같은 hash
        postJson(path, key(), revisionBody(2, "Second", ",\"settings\":{\"b\":1,\"a\":2}"))
                .andExpect(status().isCreated());
        postJson(path, key(), revisionBody(3, "Second", ",\"settings\":{\"a\":2,\"b\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details[0].reason").value("NO_CHANGES"));

        JsonNode list = json(send(get(path)).andExpect(status().isOk()));
        assertThat(list.get("totalElements").asInt()).isEqualTo(3);
        assertThat(list.get("items").get(0).get("revisionNo").asInt()).isEqualTo(3);
        send(get(path + "/1")).andExpect(status().isOk()).andExpect(jsonPath("$.workingTitle").value("Working Title"));
        send(get(path + "/9")).andExpect(status().isNotFound());
        send(get("/api/v1/contents/999999999/revisions")).andExpect(status().isNotFound());
        postJson("/api/v1/contents/999999999/revisions", key(), revisionBody(1, "x", ""))
                .andExpect(status().isNotFound());
    }

    @Test
    void baseRunMustBeASucceededRunOfTheSameContent() throws Exception {
        JsonNode content = createContent(channel());
        long id = content.get("id").asLong();
        long revisionId = content.at("/currentRevision/id").asLong();
        String path = "/api/v1/contents/" + id + "/revisions";
        JsonNode other = createContent(channel());
        long foreignRun = fx.run(other.get("id").asLong(), other.at("/currentRevision/id").asLong(), 1);
        fx.finish(other.get("id").asLong(), foreignRun, "SUCCEEDED");
        postJson(path, key(), revisionBody(1, "Base", ",\"baseRunId\":\"" + foreignRun + "\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("baseRunId"));
        long failed = fx.run(id, revisionId, 1);
        fx.finish(id, failed, "FAILED");
        postJson(path, key(), revisionBody(1, "Base", ",\"baseRunId\":\"" + failed + "\""))
                .andExpect(status().isBadRequest());
        long succeeded = fx.run(id, revisionId, 2);
        fx.finish(id, succeeded, "SUCCEEDED");
        postJson(path, key(), revisionBody(1, "Base", ",\"baseRunId\":\"" + succeeded + "\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.baseRunId").value(String.valueOf(succeeded)));
    }

    @Test
    void producingAndArchivedContentsRejectRevisionsAndArchive() throws Exception {
        JsonNode content = createContent(channel());
        long id = content.get("id").asLong();
        String path = "/api/v1/contents/" + id;
        long run = fx.activate(id, content.at("/currentRevision/id").asLong(), 1);
        send(get(path)).andExpect(jsonPath("$.status").value("PRODUCING"))
                .andExpect(jsonPath("$.activeRunId").value(String.valueOf(run)));
        postJson(path + "/revisions", key(), revisionBody(1, "While producing", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        postJson(path + "/archive", null, "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        fx.finish(id, run, "CANCELLED");
        long version = json(send(get(path))).get("version").asLong();
        postJson(path + "/archive", null, "{\"version\":" + (version + 5) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        postJson(path + "/archive", null, "{\"version\":" + version + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(version + 1));
        postJson(path + "/archive", null, "{\"version\":" + (version + 1) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        postJson(path + "/revisions", key(), revisionBody(1, "After archive", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        postInvalid(path + "/archive", null, "{}").andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- FD-16
    @Test
    void channelWithActiveRunCannotBeArchived() throws Exception {
        long channelId = channel();
        JsonNode content = createContent(channelId);
        long id = content.get("id").asLong();
        long run = fx.activate(id, content.at("/currentRevision/id").asLong(), 1);
        long version = channels.get(channelId).channel().version();
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/archive").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(ApiTestSupport.matchesContract());
        assertThat(channels.get(channelId).channel().status()).isEqualTo("ACTIVE");
        // 일시정지는 허용(FD-16은 보관만 차단)
        channels.pause(channelId, version, Actor.admin(admin.id()));
        fx.finish(id, run, "SUCCEEDED");
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/archive").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + channels.get(channelId).channel().version() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(ApiTestSupport.matchesContract());
        // 보관 채널 콘텐츠는 조회 가능, revision 생성은 거절
        send(get("/api/v1/contents/" + id)).andExpect(status().isOk());
        postJson("/api/v1/contents/" + id + "/revisions", key(), revisionBody(1, "x", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREREQUISITE_NOT_MET"));
    }

    // ---------------------------------------------------------------- RV-B-01 고아 run fail closed
    @Test
    void orphanRunBlocksChannelArchive() throws Exception {
        long channelId = channel();
        JsonNode content = createContent(channelId);
        long id = content.get("id").asLong();
        long orphan = fx.run(id, content.at("/currentRevision/id").asLong(), 1); // 포인터 없는 QUEUED run
        send(get("/api/v1/contents/" + id)).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.activeRunId").isEmpty());
        long version = channels.get(channelId).channel().version();
        mockMvc.perform(post("/api/v1/channels/" + channelId + "/archive").with(ApiTestSupport.as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(ApiTestSupport.matchesContract());
        assertThat(channels.get(channelId).channel().status()).isEqualTo("ACTIVE");
        // 자동 정리하지 않는다
        assertThat(jdbc.queryForObject("SELECT status FROM content_run WHERE id = ?", String.class, orphan))
                .isEqualTo("QUEUED");
    }

    @Test
    void orphanRunBlocksContentArchiveAndRevision() throws Exception {
        JsonNode content = createContent(channel());
        long id = content.get("id").asLong();
        String path = "/api/v1/contents/" + id;
        fx.run(id, content.at("/currentRevision/id").asLong(), 1);
        postJson(path + "/archive", null, "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        postJson(path + "/revisions", key(), revisionBody(1, "Blocked", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        send(get(path)).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.currentRevision.revisionNo").value(1));
    }

    // ---------------------------------------------------------------- RV-B-05 유니코드·크기 경계, 지문 계약
    private static String emoji(int count) {
        return new String(Character.toChars(0x1F600)).repeat(count);
    }

    @Test
    void lengthsAreCountedInCodePoints() throws Exception {
        long channelId = channel();
        // 보조 평면 문자 255개 = UTF-16 510단위. 코드포인트 기준으로 허용(OpenAPI maxLength·VARCHAR(255)와 같은 단위)
        JsonNode topic = json(postJson("/api/v1/topics", key(), topicBody(channelId, emoji(255)))
                .andExpect(status().isCreated()));
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(title) FROM topic WHERE id = ?", Integer.class,
                topic.get("id").asLong())).isEqualTo(255);
        postInvalid("/api/v1/topics", key(), topicBody(channelId, emoji(256))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("title"));
        String topicId = topic.get("id").asString();
        String body = "{\"channelId\":\"" + channelId + "\",\"topicId\":\"" + topicId + "\",\"contentType\":\"SHORTS\","
                + "\"workingTitle\":\"" + emoji(255) + "\",\"brief\":\"" + emoji(4000) + "\",\"targetDurationSeconds\":50}";
        JsonNode content = json(postJson("/api/v1/contents", key(), body).andExpect(status().isCreated()));
        assertThat(content.at("/currentRevision/brief").asString()).isEqualTo(emoji(4000));
        postInvalid("/api/v1/contents", key(), body.replace(emoji(4000), emoji(4001)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("brief"));
        postInvalid("/api/v1/contents", key(), body.replace(emoji(255) + "\",\"brief", emoji(256) + "\",\"brief"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("workingTitle"));
    }

    @Test
    void unpairedSurrogatesAreRejectedAsValidationErrors() throws Exception {
        long channelId = channel();
        postInvalid("/api/v1/topics", key(), topicBody(channelId, "bad \\ud83d title"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String topicId = createTopic(channelId, "Surrogate").get("id").asString();
        postInvalid("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"settings\":{\"k\":\"\\ude00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("settings"));
        postInvalid("/api/v1/contents", key(),
                contentBody(channelId, topicId, "").replace("Explain the topic.", "x\\udc00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("brief"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channelId))
                .isZero();
    }

    @Test
    void settingsSizeLimitIsCheckedOnCanonicalJson() throws Exception {
        long channelId = channel();
        String topicId = createTopic(channelId, "Settings").get("id").asString();
        // 정규화 JSON {"k":"xxx..."} = 8바이트 + 값 길이. 16,384바이트 경계
        String fits = ",\"settings\":{\"k\":\"" + "x".repeat(16384 - 8) + "\"}";
        String over = ",\"settings\":{\"k\":\"" + "x".repeat(16384 - 7) + "\"}";
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, fits)).andExpect(status().isCreated());
        postJson("/api/v1/contents", key(), contentBody(channelId, topicId, over)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("settings"));
        // 많은 키·깊은 중첩도 결정적: 키 순서만 다른 같은 settings는 같은 revision hash
        StringBuilder forward = new StringBuilder("{");
        StringBuilder backward = new StringBuilder("{");
        for (int i = 0; i < 300; i++) {
            forward.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":{\"b\":").append(i).append(",\"a\":[1,2]}");
            int j = 299 - i;
            backward.append(i == 0 ? "" : ",").append("\"k").append(j).append("\":{\"a\":[1,2],\"b\":").append(j).append("}");
        }
        forward.append("}");
        backward.append("}");
        JsonNode a = json(postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"settings\":" + forward))
                .andExpect(status().isCreated()));
        JsonNode b = json(postJson("/api/v1/contents", key(), contentBody(channelId, topicId, ",\"settings\":" + backward))
                .andExpect(status().isCreated()));
        String revisionA = a.at("/currentRevision/id").asString();
        String revisionB = b.at("/currentRevision/id").asString();
        assertThat(jdbc.queryForObject("SELECT settings_json FROM content_revision WHERE id = ?", String.class,
                Long.parseLong(revisionA))).isEqualTo(jdbc.queryForObject(
                        "SELECT settings_json FROM content_revision WHERE id = ?", String.class, Long.parseLong(revisionB)));
    }

    @Test
    void fingerprintUsesRequestValuesNotStoredValues() throws Exception {
        long channelId = channel();
        String k = key();
        postJson("/api/v1/topics", k, topicBody(channelId, "Same Title")).andExpect(status().isCreated());
        // 저장값(strip 후)은 같지만 요청값이 다르므로 같은 키에서 409(지문 v1 계약)
        postJson("/api/v1/topics", k, topicBody(channelId, " Same Title")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        String topicId = createTopic(channelId, "FP").get("id").asString();
        String k2 = key();
        postJson("/api/v1/contents", k2, contentBody(channelId, topicId, "")).andExpect(status().isCreated());
        // language 생략(채널 기본 en)과 명시 "en"은 저장 결과가 같아도 다른 요청
        postJson("/api/v1/contents", k2, contentBody(channelId, topicId, ",\"language\":\"en\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        // JSON 키 순서·공백만 다른 같은 요청은 재생
        String reordered = "{ \"targetDurationSeconds\": 50, \"brief\": \"Explain the topic.\", \"workingTitle\": "
                + "\"Working Title\", \"contentType\": \"SHORTS\", \"topicId\": \"" + topicId + "\", \"channelId\": \""
                + channelId + "\" }";
        postJson("/api/v1/contents", k2, reordered).andExpect(status().isOk());
    }
}
