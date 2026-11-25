package com.aistudio.content;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.content.command.IdempotentCommands;
import com.aistudio.security.CurrentActor;
import com.aistudio.shared.api.ApiId;
import com.aistudio.shared.api.PageResponse;
import com.aistudio.shared.api.PageResponse.PageQuery;

/**
 * 콘텐츠·revision API (T03 OpenAPI 초안 listContents/createContent/getContent/archiveContent,
 * listContentRevisions/createContentRevision/getContentRevision). Run API는 T03-C 범위.
 * budgetSummary는 T03-D(비용) 소유 필드라 이 단계에서 응답하지 않는다(보고서 계약 반영 요청 참조).
 */
@RestController
@RequestMapping("/api/v1/contents")
class ContentController {

    private static final String LANGUAGE = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

    record RevisionResponse(String id, String contentId, int revisionNo, String workingTitle, String brief,
            String language, int targetDurationSeconds, Map<String, Object> settings, String baseRunId,
            String revisionHash, Instant createdAt) {
    }

    record ContentResponse(String id, String channelId, String topicId, String contentType, String title,
            String status, long version, RevisionResponse currentRevision, String activeRunId) {
    }

    record ContentCreateRequest(@NotNull @Pattern(regexp = ApiId.PATTERN) String channelId,
            @NotNull @Pattern(regexp = ApiId.PATTERN) String topicId,
            @NotNull @Pattern(regexp = "SHORTS|LONGFORM") String contentType,
            @NotBlank @Size(max = 255) String workingTitle, @NotBlank @Size(max = 4000) String brief,
            @Size(min = 1, max = 16) @Pattern(regexp = LANGUAGE) String language,
            @NotNull @Min(1) @Max(14400) Integer targetDurationSeconds, Map<String, Object> settings,
            @Min(1) Integer channelConfigRevisionNo, @Size(min = 1, max = 500) String changeReason) {
    }

    record RevisionCreateRequest(@NotNull @Min(1) Integer baseRevisionNo,
            @NotBlank @Size(max = 255) String workingTitle, @NotBlank @Size(max = 4000) String brief,
            @NotNull @Size(min = 1, max = 16) @Pattern(regexp = LANGUAGE) String language,
            @NotNull @Min(1) @Max(14400) Integer targetDurationSeconds, Map<String, Object> settings,
            @Pattern(regexp = ApiId.PATTERN) String baseRunId, @Size(min = 1, max = 500) String changeReason) {
    }

    record VersionRequest(@NotNull @Min(0) Long version) {
    }

    private final ContentService service;

    ContentController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<ContentResponse> list(
            @RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String channelId,
            @RequestParam(required = false) @Pattern(regexp = ApiId.PATTERN) String topicId,
            @RequestParam(required = false) @Pattern(regexp = "SHORTS|LONGFORM") String contentType,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|PRODUCING|ARCHIVED") String status,
            @RequestParam(required = false) @Size(min = 1, max = 255) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Long channel = ApiId.optional("channelId", channelId);
        Long topic = ApiId.optional("topicId", topicId);
        PageQuery query = new PageQuery(page, size);
        return PageResponse.of(service.list(channel, topic, contentType, status, q, query), query,
                service.count(channel, topic, contentType, status, q), this::toResponse);
    }

    @PostMapping
    ResponseEntity<ContentResponse> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IdempotentCommands.KEY_PATTERN) String idempotencyKey,
            @Valid @RequestBody ContentCreateRequest body) {
        ContentService.ContentResult result = service.create(new ContentService.CreateContent(idempotencyKey,
                ApiId.parse("channelId", body.channelId()), ApiId.parse("topicId", body.topicId()), body.contentType(),
                body.workingTitle(), body.brief(), body.language(), body.targetDurationSeconds(), body.settings(),
                body.channelConfigRevisionNo(), body.changeReason()), CurrentActor.actor());
        ContentResponse response = toResponse(result.view());
        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/contents/" + response.id())).body(response);
    }

    @GetMapping("/{contentId}")
    ContentResponse get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String contentId) {
        return toResponse(service.get(ApiId.parse("contentId", contentId)));
    }

    @PostMapping("/{contentId}/archive")
    ContentResponse archive(@PathVariable @Pattern(regexp = ApiId.PATTERN) String contentId,
            @Valid @RequestBody VersionRequest body) {
        return toResponse(service.archive(ApiId.parse("contentId", contentId), body.version(), CurrentActor.actor()));
    }

    @GetMapping("/{contentId}/revisions")
    PageResponse<RevisionResponse> revisions(@PathVariable @Pattern(regexp = ApiId.PATTERN) String contentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        long id = ApiId.parse("contentId", contentId);
        PageQuery query = new PageQuery(page, size);
        return PageResponse.of(service.revisions(id, query), query, service.countRevisions(id), this::toResponse);
    }

    @PostMapping("/{contentId}/revisions")
    ResponseEntity<RevisionResponse> createRevision(@PathVariable @Pattern(regexp = ApiId.PATTERN) String contentId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IdempotentCommands.KEY_PATTERN) String idempotencyKey,
            @Valid @RequestBody RevisionCreateRequest body) {
        long id = ApiId.parse("contentId", contentId);
        ContentService.RevisionResult result = service.createRevision(new ContentService.CreateRevision(
                idempotencyKey, id, body.baseRevisionNo(), body.workingTitle(), body.brief(), body.language(),
                body.targetDurationSeconds(), body.settings(), ApiId.optional("baseRunId", body.baseRunId()),
                body.changeReason()), CurrentActor.actor());
        RevisionResponse response = toResponse(result.revision());
        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/contents/" + id + "/revisions/" + response.revisionNo()))
                .body(response);
    }

    @GetMapping("/{contentId}/revisions/{revisionNo}")
    RevisionResponse revision(@PathVariable @Pattern(regexp = ApiId.PATTERN) String contentId,
            @PathVariable @Min(1) int revisionNo) {
        return toResponse(service.revision(ApiId.parse("contentId", contentId), revisionNo));
    }

    private ContentResponse toResponse(ContentService.ContentView view) {
        Content c = view.content();
        return new ContentResponse(String.valueOf(c.id()), String.valueOf(c.channelId()), String.valueOf(c.topicId()),
                c.contentType(), c.title(), c.status(), c.version(), toResponse(view.currentRevision()),
                c.activeRunId() == null ? null : String.valueOf(c.activeRunId()));
    }

    private RevisionResponse toResponse(ContentRevision r) {
        return new RevisionResponse(String.valueOf(r.id()), String.valueOf(r.contentId()), r.revisionNo(),
                r.workingTitle(), r.brief(), r.language(), r.targetDurationSeconds(), service.settings(r),
                r.baseRunId() == null ? null : String.valueOf(r.baseRunId()), r.revisionHash(), r.createdAt());
    }
}
