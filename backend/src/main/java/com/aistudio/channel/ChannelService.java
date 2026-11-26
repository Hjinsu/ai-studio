package com.aistudio.channel;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.PageResponse.PageQuery;
import com.aistudio.shared.db.BudgetControlLock;

import tools.jackson.databind.json.JsonMapper;

/**
 * 채널과 설정 revision (ADR-034, ADR-042, ADR-059). 물리 삭제 없음, ARCHIVED는 종료 상태(복원·수정 금지).
 */
@Service
public class ChannelService {

    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    private final ChannelMapper mapper;
    private final BudgetControlLock controlLock;
    private final AuditService audit;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    ChannelService(ChannelMapper mapper, BudgetControlLock controlLock, AuditService audit, JsonMapper jsonMapper,
            Clock clock) {
        this.mapper = mapper;
        this.controlLock = controlLock;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public record ChannelView(Channel channel, int currentConfigRevisionNo) {
    }

    @Transactional
    public ChannelView create(String name, String language, String timezone, ChannelSettings initialConfig,
            Actor actor) {
        String trimmedName = validName(name);
        validateLanguage(language);
        validateTimezone(timezone);
        ChannelSettings settings = initialConfig == null ? ChannelSettings.defaults() : initialConfig;
        settings.validate();
        Instant now = clock.instant();
        ChannelMapper.NewChannel row = new ChannelMapper.NewChannel(trimmedName, language, timezone, now);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A channel with the same name already exists.");
        }
        insertConfig(row.id, 1, settings, "initial", actor, now);
        audit.record(actor, "CHANNEL_CREATE", "CHANNEL", row.id, Map.of("name", trimmedName));
        return new ChannelView(mapper.findById(row.id), 1);
    }

    @Transactional(readOnly = true)
    public ChannelView get(long id) {
        Channel channel = mapper.findById(id);
        if (channel == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new ChannelView(channel, mapper.maxConfigRevision(id));
    }

    @Transactional(readOnly = true)
    public java.util.List<ChannelView> list(String status, PageQuery page) {
        return mapper.list(status, page.offset(), page.size()).stream()
                .map(channel -> new ChannelView(channel, mapper.maxConfigRevision(channel.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public long count(String status) {
        return mapper.count(status);
    }

    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return mapper.countByStatus(status);
    }

    @Transactional
    public ChannelView update(long id, long version, String name, String language, String timezone, Actor actor) {
        Channel channel = mapper.lockById(id);
        requireMutable(channel);
        requireVersion(channel, version);
        String newName = name == null ? channel.name() : validName(name);
        String newLanguage = language == null ? channel.language() : language;
        String newTimezone = timezone == null ? channel.timezone() : timezone;
        validateLanguage(newLanguage);
        validateTimezone(newTimezone);
        try {
            mapper.updateInfo(id, version, newName, newLanguage, newTimezone, clock.instant());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A channel with the same name already exists.");
        }
        audit.record(actor, "CHANNEL_UPDATE", "CHANNEL", id, null);
        return get(id);
    }

    /**
     * ACTIVE → PAUSED. 잠금: budget_control X → channel X (T03 LOCK CTRL, FD-02). 진행 중 예약·정산(control S)과
     * 직렬화된다.
     */
    @Transactional
    public ChannelView pause(long id, long version, Actor actor) {
        return changeStatus(id, version, Channel.ACTIVE, Channel.PAUSED, "CHANNEL_PAUSE", actor);
    }

    @Transactional
    public ChannelView resume(long id, long version, Actor actor) {
        return changeStatus(id, version, Channel.PAUSED, Channel.ACTIVE, "CHANNEL_RESUME", actor);
    }

    /**
     * 보관(종료 상태). 잠금: budget_control X → channel X. 활성 run이 있는 채널은 409 INVALID_STATE_TRANSITION(FD-16).
     * 활성 run 확인은 channel X 획득 뒤의 잠금 없는 읽기로, channel S를 요구하는 run 생성·콘텐츠 작성과 직렬화된다.
     */
    @Transactional
    public ChannelView archive(long id, long version, Actor actor) {
        controlLock.exclusive();
        Channel channel = mapper.lockById(id);
        requireMutable(channel);
        requireVersion(channel, version);
        if (mapper.countActiveRuns(id) > 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Channels with an active content run cannot be archived.");
        }
        Instant now = clock.instant();
        mapper.updateStatus(id, version, Channel.ARCHIVED, now, now);
        audit.record(actor, "CHANNEL_ARCHIVE", "CHANNEL", id, Map.of("from", channel.status()));
        return get(id);
    }

    private ChannelView changeStatus(long id, long version, String from, String to, String action, Actor actor) {
        controlLock.exclusive();
        Channel channel = mapper.lockById(id);
        requireMutable(channel);
        requireVersion(channel, version);
        if (!channel.status().equals(from)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Channel status " + channel.status() + " cannot change to " + to + ".");
        }
        Instant now = clock.instant();
        mapper.updateStatus(id, version, to, null, now);
        audit.record(actor, action, "CHANNEL", id, Map.of("from", from, "to", to));
        return get(id);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ChannelConfig configSnapshot(long channelId, long id) {
        ChannelConfig config = mapper.findConfigById(channelId, id);
        if (config == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Channel configuration snapshot was not found.");
        }
        return config;
    }

    @Transactional(readOnly = true)
    public ChannelConfig currentConfig(long channelId) {
        requireExists(channelId);
        return mapper.latestConfig(channelId);
    }

    @Transactional(readOnly = true)
    public ChannelConfig config(long channelId, int revisionNo) {
        requireExists(channelId);
        ChannelConfig config = mapper.findConfig(channelId, revisionNo);
        if (config == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return config;
    }

    @Transactional(readOnly = true)
    public java.util.List<ChannelConfig> configs(long channelId, PageQuery page) {
        requireExists(channelId);
        return mapper.listConfigs(channelId, page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long countConfigs(long channelId) {
        return mapper.countConfigs(channelId);
    }

    /** 새 설정 revision. channel 행 잠금 + baseRevisionNo가 현재 최신인지 확인(ADR-059). 기존 revision은 불변. */
    @Transactional
    public ChannelConfig createConfigRevision(long channelId, int baseRevisionNo, ChannelSettings settings,
            String changeNote, Actor actor) {
        Channel channel = mapper.lockById(channelId);
        requireMutable(channel);
        settings.validate();
        int current = mapper.maxConfigRevision(channelId);
        if (baseRevisionNo != current) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The channel configuration was changed by another request. Reload and retry.");
        }
        Instant now = clock.instant();
        try {
            insertConfig(channelId, current + 1, settings, changeNote, actor, now);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The channel configuration was changed by another request. Reload and retry.");
        }
        audit.record(actor, "CHANNEL_CONFIG_REVISE", "CHANNEL", channelId, Map.of("revisionNo", current + 1));
        return mapper.findConfig(channelId, current + 1);
    }

    /**
     * 예약 트랜잭션에서 채널 상태를 공유 잠금으로 확인한다(ADR-042, 잠금 순서 control → channel). ACTIVE만 신규 예약 가능.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Channel lockForReservation(long channelId) {
        return mapper.lockSharedById(channelId);
    }

    /** 정책 생성 시 채널 존재·보관 여부 확인(공유 잠금). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Channel lockShared(long channelId) {
        Channel channel = mapper.lockSharedById(channelId);
        if (channel == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Channel not found.");
        }
        return channel;
    }

    /**
     * 콘텐츠·revision 작성 TX에서 현재(최신) 설정 revision을 고정한다. 호출자가 먼저 {@link #lockShared}로 channel S를
     * 잡아 설정 revision 생성(channel X)과 직렬화한다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ChannelConfig latestConfigInTransaction(long channelId) {
        ChannelConfig config = mapper.latestConfig(channelId);
        if (config == null) {
            throw new IllegalStateException("Channel " + channelId + " has no configuration revision.");
        }
        return config;
    }

    private void insertConfig(long channelId, int revisionNo, ChannelSettings settings, String changeNote, Actor actor,
            Instant now) {
        String providerJson = settings.providerOptions() == null ? null
                : jsonMapper.writeValueAsString(settings.providerOptions());
        mapper.insertConfig(new ChannelConfig(null, channelId, revisionNo, settings.normalizedContentType(),
                settings.shortsMinSeconds(), settings.shortsMaxSeconds(), settings.longformMinSeconds(),
                settings.longformMaxSeconds(), blankToNull(settings.contentStrategy()),
                blankToNull(settings.targetAudience()), blankToNull(settings.narrationStyle()),
                blankToNull(settings.visualStyle()), settings.syntheticDisclosureDefault(), providerJson,
                blankToNull(changeNote), actor.userId(), now));
    }

    private void requireExists(long channelId) {
        if (mapper.findById(channelId) == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static void requireMutable(Channel channel) {
        if (channel == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (Channel.ARCHIVED.equals(channel.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Archived channels cannot be modified.");
        }
    }

    private static void requireVersion(Channel channel, long version) {
        if (channel.version() != version) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The channel was changed by another request. Reload and retry.");
        }
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw ApiException.validation("name", "must be 1 to 100 characters");
        }
        return trimmed;
    }

    private static void validateLanguage(String language) {
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            throw ApiException.validation("language", "must be a BCP 47 language tag (e.g. en, en-US)");
        }
    }

    static void validateTimezone(String timezone) {
        try {
            if (timezone == null || timezone.length() > 64) {
                throw new DateTimeException("invalid");
            }
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.validation("timezone", "must be a valid IANA time zone");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
