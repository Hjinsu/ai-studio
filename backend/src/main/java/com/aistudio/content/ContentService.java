package com.aistudio.content;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelConfig;
import com.aistudio.channel.ChannelService;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.content.command.IdempotentCommands;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.ErrorDetail;
import com.aistudio.shared.api.PageResponse.PageQuery;
import com.aistudio.shared.db.LockRetryingTransactions;
import com.aistudio.topic.Topic;
import com.aistudio.topic.TopicService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 콘텐츠와 불변 revision (T03-B, P2/P2'/P10). 잠금 순서: channel S → content X(T03 LOCK Q0). 콘텐츠 생성은
 * content·revision 1·현재 포인터·명령 로그를 한 TX에 커밋한다. revision 변경은 baseRevisionNo로 동시 변경을 막고
 * 포인터·제목 복제본·version을 원자 갱신한다. Run 생성·실행은 T03-C 범위다.
 */
@Service
public class ContentService {

    static final String CREATE_CONTENT = "CREATE_CONTENT";
    static final String CREATE_REVISION = "CREATE_REVISION";
    static final int SETTINGS_MAX_BYTES = 16 * 1024;
    static final int BRIEF_MAX = 4000;

    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ContentMapper mapper;
    private final ChannelService channels;
    private final TopicService topics;
    private final IdempotentCommands commands;
    private final LockRetryingTransactions transactions;
    private final AuditService audit;
    private final JsonMapper jsonMapper;

    ContentService(ContentMapper mapper, ChannelService channels, TopicService topics, IdempotentCommands commands,
            LockRetryingTransactions transactions, AuditService audit, JsonMapper jsonMapper) {
        this.mapper = mapper;
        this.channels = channels;
        this.topics = topics;
        this.commands = commands;
        this.transactions = transactions;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    public record ContentView(Content content, ContentRevision currentRevision) {
    }

    public record ContentResult(ContentView view, boolean replayed) {
    }

    public record RevisionResult(ContentRevision revision, boolean replayed) {
    }

    public record CreateContent(String requestKey, long channelId, long topicId, String contentType,
            String workingTitle, String brief, String language, Integer targetDurationSeconds,
            Map<String, Object> settings, Integer channelConfigRevisionNo, String changeReason) {
    }

    public record CreateRevision(String requestKey, long contentId, int baseRevisionNo, String workingTitle,
            String brief, String language, Integer targetDurationSeconds, Map<String, Object> settings,
            Long baseRunId, String changeReason) {
    }

    /** 정규화된 revision 입력(저장값). */
    private record RevisionInput(String workingTitle, String brief, String language, int targetDurationSeconds,
            Map<String, Object> settings, String settingsJson, String changeReason) {
    }

    // ---------------------------------------------------------------- 생성
    public ContentResult create(CreateContent c, Actor actor) {
        String contentType = c.contentType();
        if (!Content.SHORTS.equals(contentType) && !Content.LONGFORM.equals(contentType)) {
            throw ApiException.validation("contentType", "must be SHORTS or LONGFORM");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", CREATE_CONTENT);
        fields.put("channelId", c.channelId());
        fields.put("topicId", c.topicId());
        fields.put("contentType", contentType);
        fields.put("workingTitle", c.workingTitle());
        fields.put("brief", c.brief());
        fields.put("language", c.language());
        fields.put("targetDurationSeconds", c.targetDurationSeconds());
        fields.put("settings", c.settings());
        fields.put("channelConfigRevisionNo", c.channelConfigRevisionNo());
        fields.put("changeReason", c.changeReason());
        IdempotentCommands.Command log = new IdempotentCommands.Command(c.requestKey(), CREATE_CONTENT, "CONTENT",
                fingerprint(fields), actor);

        IdempotentCommands.Result result = commands.execute(log, () -> {
            Channel channel = channels.lockShared(c.channelId());
            if (Channel.ARCHIVED.equals(channel.status())) {
                throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Archived channels cannot receive contents.");
            }
            Topic topic = topics.findInChannel(c.topicId(), c.channelId());
            if (topic == null) {
                throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The topic was not found in the channel.");
            }
            ChannelConfig config = channels.latestConfigInTransaction(c.channelId());
            if (c.channelConfigRevisionNo() != null && c.channelConfigRevisionNo() != config.revisionNo()) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "The channel configuration was changed by another request. Reload and retry.");
            }
            RevisionInput input = normalize(c.workingTitle(), c.brief(),
                    c.language() == null ? channel.language() : c.language(), c.targetDurationSeconds(),
                    c.settings(), c.changeReason(), contentType, config);

            ContentMapper.NewContent row = new ContentMapper.NewContent(c.channelId(), c.topicId(), contentType,
                    input.workingTitle(), actor.userId());
            mapper.insertContent(row);
            ContentMapper.NewRevision revision = newRevision(row.id, c.channelId(), 1, config, null, input, actor);
            mapper.insertRevision(revision);
            if (mapper.setInitialRevision(row.id, revision.id, input.workingTitle()) != 1) {
                throw new IllegalStateException("content " + row.id + " initial revision pointer was not set");
            }
            audit.record(actor, "CONTENT_CREATE", "CONTENT", row.id,
                    Map.of("channelId", c.channelId(), "topicId", c.topicId(), "contentType", contentType));
            return row.id;
        }, null);
        return new ContentResult(get(result.targetId()), result.replayed());
    }

    public RevisionResult createRevision(CreateRevision c, Actor actor) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", CREATE_REVISION);
        fields.put("contentId", c.contentId());
        fields.put("baseRevisionNo", c.baseRevisionNo());
        fields.put("workingTitle", c.workingTitle());
        fields.put("brief", c.brief());
        fields.put("language", c.language());
        fields.put("targetDurationSeconds", c.targetDurationSeconds());
        fields.put("settings", c.settings());
        fields.put("baseRunId", c.baseRunId());
        fields.put("changeReason", c.changeReason());
        IdempotentCommands.Command log = new IdempotentCommands.Command(c.requestKey(), CREATE_REVISION,
                "CONTENT_REVISION", fingerprint(fields), actor);

        IdempotentCommands.Result result = commands.execute(log, () -> {
            Content unlocked = requireContent(mapper.findById(c.contentId()));
            // channel S → content X. channel_id는 불변이므로 잠금 전 읽은 값으로 채널을 먼저 잠근다
            Channel channel = channels.lockShared(unlocked.channelId());
            Content content = requireContent(mapper.lockById(c.contentId()));
            requireDraft(content, "revised");
            if (Channel.ARCHIVED.equals(channel.status())) {
                throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Contents of archived channels cannot be revised.");
            }
            ContentRevision current = mapper.findRevisionById(content.currentRevisionId());
            if (current.revisionNo() != c.baseRevisionNo()) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "The content was revised by another request. Reload and retry.");
            }
            ChannelConfig config = channels.latestConfigInTransaction(content.channelId());
            RevisionInput input = normalize(c.workingTitle(), c.brief(), c.language(), c.targetDurationSeconds(),
                    c.settings(), c.changeReason(), content.contentType(), config);
            if (c.baseRunId() != null && !"SUCCEEDED".equals(mapper.runStatus(content.id(), c.baseRunId()))) {
                throw ApiException.validation("baseRunId", "must be a succeeded run of this content");
            }
            ContentMapper.NewRevision revision = newRevision(content.id(), content.channelId(),
                    current.revisionNo() + 1, config, c.baseRunId(), input, actor);
            if (revision.revisionHash.equals(current.revisionHash())) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                        "The revision is identical to the current revision.",
                        List.of(new ErrorDetail(null, "NO_CHANGES")));
            }
            mapper.insertRevision(revision);
            if (mapper.setCurrentRevision(content.id(), content.version(), revision.id, input.workingTitle()) != 1) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "The content was changed by another request. Reload and retry.");
            }
            audit.record(actor, "CONTENT_REVISE", "CONTENT", content.id(), Map.of("revisionNo", revision.revisionNo));
            return revision.id;
        }, null);
        ContentRevision revision = mapper.findRevisionById(result.targetId());
        return new RevisionResult(revision, result.replayed());
    }

    /** DRAFT → ARCHIVED (P10). 잠금: channel S → content X. PRODUCING·ARCHIVED는 409 INVALID_STATE_TRANSITION. */
    public ContentView archive(long contentId, long version, Actor actor) {
        transactions.run(() -> {
            Content unlocked = requireContent(mapper.findById(contentId));
            channels.lockShared(unlocked.channelId());
            Content content = requireContent(mapper.lockById(contentId));
            requireDraft(content, "archived");
            if (content.version() != version) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "The content was changed by another request. Reload and retry.");
            }
            if (mapper.archive(contentId, version) != 1) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "The content was changed by another request. Reload and retry.");
            }
            audit.record(actor, "CONTENT_ARCHIVE", "CONTENT", contentId, null);
        });
        return get(contentId);
    }

    // ---------------------------------------------------------------- 조회
    @Transactional(readOnly = true)
    public ContentView get(long contentId) {
        Content content = requireContent(mapper.findById(contentId));
        return new ContentView(content, mapper.findRevisionById(content.currentRevisionId()));
    }

    @Transactional(readOnly = true)
    public List<ContentView> list(Long channelId, Long topicId, String contentType, String status, String q,
            PageQuery page) {
        List<Content> rows = mapper.list(channelId, topicId, contentType, status, likePattern(q), page.offset(),
                page.size());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ContentRevision> revisions = mapper
                .findRevisionsByIds(rows.stream().map(Content::currentRevisionId).toList()).stream()
                .collect(Collectors.toMap(ContentRevision::id, Function.identity()));
        return rows.stream().map(c -> new ContentView(c, revisions.get(c.currentRevisionId()))).toList();
    }

    @Transactional(readOnly = true)
    public long count(Long channelId, Long topicId, String contentType, String status, String q) {
        return mapper.count(channelId, topicId, contentType, status, likePattern(q));
    }

    @Transactional(readOnly = true)
    public ContentRevision revision(long contentId, int revisionNo) {
        requireContent(mapper.findById(contentId));
        ContentRevision revision = mapper.findRevision(contentId, revisionNo);
        if (revision == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return revision;
    }

    @Transactional(readOnly = true)
    public List<ContentRevision> revisions(long contentId, PageQuery page) {
        requireContent(mapper.findById(contentId));
        return mapper.listRevisions(contentId, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long countRevisions(long contentId) {
        return mapper.countRevisions(contentId);
    }

    /** 저장된 settings_json(정규화 JSON)을 응답용 객체로. 없으면 빈 객체. */
    public Map<String, Object> settings(ContentRevision revision) {
        return revision.settingsJson() == null ? Map.of() : jsonMapper.readValue(revision.settingsJson(), MAP);
    }

    // ---------------------------------------------------------------- 내부
    private RevisionInput normalize(String workingTitle, String brief, String language, Integer duration,
            Map<String, Object> settings, String changeReason, String contentType, ChannelConfig config) {
        String title = workingTitle == null ? "" : lines(workingTitle).strip();
        if (title.isEmpty() || title.length() > 255) {
            throw ApiException.validation("workingTitle", "must be 1 to 255 characters");
        }
        String normalizedBrief = brief == null ? "" : lines(brief);
        if (normalizedBrief.isBlank() || normalizedBrief.length() > BRIEF_MAX) {
            throw ApiException.validation("brief", "must be 1 to 4000 characters");
        }
        if (language == null || language.length() > 16 || !LANGUAGE.matcher(language).matches()) {
            throw ApiException.validation("language", "must be a BCP 47 language tag (e.g. en, en-US)");
        }
        if (duration == null) {
            throw ApiException.validation("targetDurationSeconds", "is required");
        }
        int min = Content.SHORTS.equals(contentType) ? config.shortsMinSeconds() : config.longformMinSeconds();
        int max = Content.SHORTS.equals(contentType) ? config.shortsMaxSeconds() : config.longformMaxSeconds();
        if (duration < min || duration > max) {
            throw ApiException.validation("targetDurationSeconds",
                    "must be between " + min + " and " + max + " for " + contentType);
        }
        Map<String, Object> canonicalSettings = settings == null || settings.isEmpty() ? null : settings;
        String settingsJson = null;
        if (canonicalSettings != null) {
            try {
                settingsJson = CanonicalJson.write(canonicalSettings);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("settings", e.getMessage());
            }
            if (settingsJson.getBytes(StandardCharsets.UTF_8).length > SETTINGS_MAX_BYTES) {
                throw ApiException.validation("settings", "must be at most 16KB");
            }
        }
        String reason = changeReason == null || changeReason.isBlank() ? null : lines(changeReason);
        if (reason != null && reason.length() > 500) {
            throw ApiException.validation("changeReason", "must be at most 500 characters");
        }
        return new RevisionInput(title, normalizedBrief, language, duration, canonicalSettings, settingsJson, reason);
    }

    private static ContentMapper.NewRevision newRevision(long contentId, long channelId, int revisionNo,
            ChannelConfig config, Long baseRunId, RevisionInput input, Actor actor) {
        String hash = RevisionHash.compute(new RevisionHash.Input(contentId, config.id(), baseRunId,
                input.workingTitle(), input.brief(), input.language(), input.targetDurationSeconds(),
                input.settings()));
        return new ContentMapper.NewRevision(contentId, channelId, revisionNo, config.id(), baseRunId,
                input.workingTitle(), input.brief(), input.language(), input.targetDurationSeconds(),
                input.settingsJson(), hash, RevisionHash.VERSION, input.changeReason(), actor.userId());
    }

    private static String fingerprint(Map<String, Object> fields) {
        try {
            return CanonicalJson.sha256(fields);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("settings", e.getMessage());
        }
    }

    private static Content requireContent(Content content) {
        if (content == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return content;
    }

    private static void requireDraft(Content content, String action) {
        if (!Content.DRAFT.equals(content.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Contents in status " + content.status() + " cannot be " + action + ".");
        }
    }

    /** 줄바꿈을 \n으로 통일한다(revision hash 입력 규칙). */
    private static String lines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return q.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
