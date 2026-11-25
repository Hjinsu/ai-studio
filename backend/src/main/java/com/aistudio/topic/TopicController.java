package com.aistudio.topic;

import java.net.URI;
import java.time.Instant;

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

/** Topic API (T03 OpenAPI 초안 listTopics/createTopic/getTopic). ADMIN 세션·CSRF는 SecurityConfiguration이 적용한다. */
@RestController
@RequestMapping("/api/v1/topics")
class TopicController {

    record TopicResponse(String id, String channelId, String title, String note, Instant createdAt) {

        static TopicResponse from(Topic t) {
            return new TopicResponse(String.valueOf(t.id()), String.valueOf(t.channelId()), t.title(), t.note(),
                    t.createdAt());
        }
    }

    record TopicCreateRequest(@NotNull @Pattern(regexp = ApiId.PATTERN) String channelId,
            @NotBlank @Size(max = 255) String title, @Size(min = 1, max = 500) String note) {
    }

    private final TopicService service;

    TopicController(TopicService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<TopicResponse> list(@RequestParam @Pattern(regexp = ApiId.PATTERN) String channelId,
            @RequestParam(required = false) @Size(min = 1, max = 255) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        long id = ApiId.parse("channelId", channelId);
        PageQuery query = new PageQuery(page, size);
        return PageResponse.of(service.list(id, q, query), query, service.count(id, q), TopicResponse::from);
    }

    @PostMapping
    ResponseEntity<TopicResponse> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IdempotentCommands.KEY_PATTERN) String idempotencyKey,
            @Valid @RequestBody TopicCreateRequest body) {
        TopicService.TopicResult result = service.create(new TopicService.CreateTopic(idempotencyKey,
                ApiId.parse("channelId", body.channelId()), body.title(), body.note()), CurrentActor.actor());
        TopicResponse response = TopicResponse.from(result.topic());
        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/v1/topics/" + result.topic().id())).body(response);
    }

    @GetMapping("/{topicId}")
    TopicResponse get(@PathVariable @Pattern(regexp = ApiId.PATTERN) String topicId) {
        return TopicResponse.from(service.get(ApiId.parse("topicId", topicId)));
    }
}
