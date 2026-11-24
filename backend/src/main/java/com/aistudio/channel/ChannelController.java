package com.aistudio.channel;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aistudio.security.CurrentActor;
import com.aistudio.shared.api.PageResponse;
import com.aistudio.shared.api.PageResponse.PageQuery;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 채널·설정 API (docs/api/openapi.yaml channels, channel-config). ID는 문자열로 응답한다. */
@RestController
@RequestMapping("/api/v1/channels")
class ChannelController {

    record ChannelResponse(String id, String name, String language, String timezone, String status, long version,
            int currentConfigRevisionNo, Instant createdAt, Instant updatedAt, Instant archivedAt) {

        static ChannelResponse from(ChannelService.ChannelView view) {
            Channel c = view.channel();
            return new ChannelResponse(String.valueOf(c.id()), c.name(), c.language(), c.timezone(), c.status(),
                    c.version(), view.currentConfigRevisionNo(), c.createdAt(), c.updatedAt(), c.archivedAt());
        }
    }

    record ChannelCreateRequest(@NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$") String language,
            @NotBlank @Size(max = 64) String timezone, @Valid ChannelSettings initialConfig) {
    }

    record ChannelUpdateRequest(@NotNull @Min(0) Long version, @Size(min = 1, max = 100) String name,
            @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$") String language, @Size(max = 64) String timezone) {
    }

    record VersionRequest(@NotNull @Min(0) Long version) {
    }

    record ChannelConfigResponse(String channelId, int revisionNo, String defaultContentType, int shortsMinSeconds,
            int shortsMaxSeconds, int longformMinSeconds, int longformMaxSeconds, String contentStrategy,
            String targetAudience, String narrationStyle, String visualStyle, boolean syntheticDisclosureDefault,
            Map<String, Object> providerOptions, String changeNote, String createdBy, Instant createdAt) {
    }

    record ChannelConfigCreateRequest(@NotNull @Min(1) Integer baseRevisionNo, @NotNull @Valid ChannelSettings settings,
            @Size(max = 500) String changeNote) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ChannelService service;
    private final JsonMapper jsonMapper;

    ChannelController(ChannelService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    PageResponse<ChannelResponse> list(
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|PAUSED|ARCHIVED") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageQuery query = new PageQuery(page, size);
        return PageResponse.of(service.list(status, query), query, service.count(status), ChannelResponse::from);
    }

    @PostMapping
    ResponseEntity<ChannelResponse> create(@Valid @RequestBody ChannelCreateRequest body) {
        ChannelService.ChannelView view = service.create(body.name(), body.language(), body.timezone(),
                body.initialConfig(), CurrentActor.actor());
        return ResponseEntity.created(URI.create("/api/v1/channels/" + view.channel().id()))
                .body(ChannelResponse.from(view));
    }

    @GetMapping("/{channelId}")
    ChannelResponse get(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId) {
        return ChannelResponse.from(service.get(Long.parseLong(channelId)));
    }

    @PatchMapping("/{channelId}")
    ChannelResponse update(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @Valid @RequestBody ChannelUpdateRequest body) {
        if (body.name() == null && body.language() == null && body.timezone() == null) {
            throw com.aistudio.shared.api.ApiException.validation(null, "at least one field must be provided");
        }
        return ChannelResponse.from(service.update(Long.parseLong(channelId), body.version(), body.name(),
                body.language(), body.timezone(), CurrentActor.actor()));
    }

    @PostMapping("/{channelId}/pause")
    ChannelResponse pause(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @Valid @RequestBody VersionRequest body) {
        return ChannelResponse.from(service.pause(Long.parseLong(channelId), body.version(), CurrentActor.actor()));
    }

    @PostMapping("/{channelId}/resume")
    ChannelResponse resume(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @Valid @RequestBody VersionRequest body) {
        return ChannelResponse.from(service.resume(Long.parseLong(channelId), body.version(), CurrentActor.actor()));
    }

    @PostMapping("/{channelId}/archive")
    ChannelResponse archive(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @Valid @RequestBody VersionRequest body) {
        return ChannelResponse.from(service.archive(Long.parseLong(channelId), body.version(), CurrentActor.actor()));
    }

    @GetMapping("/{channelId}/config")
    ChannelConfigResponse currentConfig(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId) {
        return toResponse(service.currentConfig(Long.parseLong(channelId)));
    }

    @GetMapping("/{channelId}/config/revisions")
    PageResponse<ChannelConfigResponse> configs(
            @PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        long id = Long.parseLong(channelId);
        PageQuery query = new PageQuery(page, size);
        return PageResponse.of(service.configs(id, query), query, service.countConfigs(id), this::toResponse);
    }

    @PostMapping("/{channelId}/config/revisions")
    ResponseEntity<ChannelConfigResponse> createConfig(
            @PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @Valid @RequestBody ChannelConfigCreateRequest body) {
        ChannelConfig config = service.createConfigRevision(Long.parseLong(channelId), body.baseRevisionNo(),
                body.settings(), body.changeNote(), CurrentActor.actor());
        return ResponseEntity.status(201).body(toResponse(config));
    }

    @GetMapping("/{channelId}/config/revisions/{revisionNo}")
    ChannelConfigResponse config(@PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String channelId,
            @PathVariable @Min(1) int revisionNo) {
        return toResponse(service.config(Long.parseLong(channelId), revisionNo));
    }

    private ChannelConfigResponse toResponse(ChannelConfig c) {
        Map<String, Object> options = c.providerOptionsJson() == null ? null
                : jsonMapper.readValue(c.providerOptionsJson(), MAP);
        return new ChannelConfigResponse(String.valueOf(c.channelId()), c.revisionNo(), c.defaultContentType(),
                c.shortsMinSeconds(), c.shortsMaxSeconds(), c.longformMinSeconds(), c.longformMaxSeconds(),
                c.contentStrategy(), c.targetAudience(), c.narrationStyle(), c.visualStyle(),
                c.syntheticDisclosureDefault(), options, c.changeNote(), String.valueOf(c.createdBy()), c.createdAt());
    }
}
