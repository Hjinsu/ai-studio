package com.aistudio.topic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aistudio.audit.AuditService;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.channel.Channel;
import com.aistudio.channel.ChannelService;
import com.aistudio.content.command.CanonicalJson;
import com.aistudio.content.command.IdempotentCommands;
import com.aistudio.content.command.Texts;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.api.ErrorDetail;
import com.aistudio.shared.api.PageResponse.PageQuery;

/**
 * Topic 등록·조회 (T03-B, P1). 잠금: channel S → topic INSERT(FK S). 같은 채널의 정규화 제목 중복은
 * UNIQUE(channel_id, normalized_hash)가 최종 방어하며 409 DUPLICATE_RESOURCE(details DUPLICATE_TOPIC:id).
 */
@Service
public class TopicService {

    static final String CREATE_TOPIC = "CREATE_TOPIC";
    static final int TITLE_MAX = 255;
    static final int NOTE_MAX = 500;

    private final TopicMapper mapper;
    private final ChannelService channels;
    private final IdempotentCommands commands;
    private final AuditService audit;

    TopicService(TopicMapper mapper, ChannelService channels, IdempotentCommands commands, AuditService audit) {
        this.mapper = mapper;
        this.channels = channels;
        this.commands = commands;
        this.audit = audit;
    }

    public record CreateTopic(String requestKey, long channelId, String title, String note) {
    }

    public record TopicResult(Topic topic, boolean replayed) {
    }

    public TopicResult create(CreateTopic command, Actor actor) {
        // 길이는 코드포인트 기준(OpenAPI maxLength, utf8mb4 VARCHAR와 같은 단위). 지문 계산 전에 유니코드를 검사한다
        String title = command.title() == null ? "" : command.title().strip();
        Texts.requireLength("title", title, 1, TITLE_MAX);
        String normalized = TopicNormalizer.normalize(title);
        if (normalized.isEmpty() || Texts.length(normalized) > TITLE_MAX) {
            throw ApiException.validation("title", "normalized title must be 1 to 255 characters");
        }
        if (command.note() != null) {
            Texts.requireLength("note", command.note(), 1, NOTE_MAX);
        }
        String note = command.note() == null || command.note().isBlank() ? null : command.note();
        String hash = TopicNormalizer.hash(TopicNormalizer.VERSION, normalized);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("command", CREATE_TOPIC);
        fields.put("channelId", command.channelId());
        fields.put("title", command.title());
        fields.put("note", command.note());
        IdempotentCommands.Command log = new IdempotentCommands.Command(command.requestKey(), CREATE_TOPIC, "TOPIC",
                CanonicalJson.sha256(fields), actor);

        IdempotentCommands.Result result = commands.execute(log, () -> {
            Channel channel = channels.lockShared(command.channelId());
            if (Channel.ARCHIVED.equals(channel.status())) {
                throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Archived channels cannot receive topics.");
            }
            Topic existing = mapper.findByHash(command.channelId(), hash);
            if (existing != null) {
                throw duplicate(existing.id());
            }
            TopicMapper.NewTopic row = new TopicMapper.NewTopic(command.channelId(), title, normalized, hash,
                    TopicNormalizer.VERSION, note, actor.userId());
            mapper.insert(row);
            audit.record(actor, "TOPIC_CREATE", "TOPIC", row.id, Map.of("channelId", command.channelId()));
            return row.id;
        }, e -> {
            if (IdempotentCommands.violates(e, "uk_topic_normalized")) {
                Topic raced = mapper.findByHash(command.channelId(), hash);
                return raced != null ? duplicate(raced.id())
                        : new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A topic with the same title already exists.");
            }
            return e;
        });
        return new TopicResult(get(result.targetId()), result.replayed());
    }

    @Transactional(readOnly = true)
    public Topic get(long id) {
        Topic topic = mapper.findById(id);
        if (topic == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return topic;
    }

    @Transactional(readOnly = true)
    public List<Topic> list(long channelId, String q, PageQuery page) {
        channels.get(channelId);
        return mapper.list(channelId, searchPattern(q), page.offset(), page.size());
    }

    @Transactional(readOnly = true)
    public long count(long channelId, String q) {
        return mapper.count(channelId, searchPattern(q));
    }

    /** 콘텐츠 생성 TX에서 topic이 같은 채널 소유인지 확인한다(복합 FK가 최종 방어). 없으면 null. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Topic findInChannel(long topicId, long channelId) {
        return mapper.findInChannel(topicId, channelId);
    }

    private static ApiException duplicate(long existingId) {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A topic with the same title already exists.",
                List.of(new ErrorDetail("title", "DUPLICATE_TOPIC:" + existingId)));
    }

    private static String searchPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return TopicNormalizer.normalize(q).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
