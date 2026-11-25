package com.aistudio.content;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface ContentMapper {

    String COLUMNS = "id, channel_id, topic_id, content_type, title, status, current_revision_id, active_run_id,"
            + " version, created_by, created_at, updated_at, archived_at";
    String REVISION_COLUMNS = "id, content_id, channel_id, revision_no, channel_config_id, base_run_id, working_title,"
            + " brief, language, target_duration_seconds, settings_json, revision_hash, hash_version, change_reason,"
            + " created_by, created_at";

    final class NewContent {
        public Long id;
        public final long channelId;
        public final long topicId;
        public final String contentType;
        public final String title;
        public final long createdBy;

        NewContent(long channelId, long topicId, String contentType, String title, long createdBy) {
            this.channelId = channelId;
            this.topicId = topicId;
            this.contentType = contentType;
            this.title = title;
            this.createdBy = createdBy;
        }
    }

    final class NewRevision {
        public Long id;
        public final long contentId;
        public final long channelId;
        public final int revisionNo;
        public final long channelConfigId;
        public final Long baseRunId;
        public final String workingTitle;
        public final String brief;
        public final String language;
        public final int targetDurationSeconds;
        public final String settingsJson;
        public final String revisionHash;
        public final int hashVersion;
        public final String changeReason;
        public final long createdBy;

        NewRevision(long contentId, long channelId, int revisionNo, long channelConfigId, Long baseRunId,
                String workingTitle, String brief, String language, int targetDurationSeconds, String settingsJson,
                String revisionHash, int hashVersion, String changeReason, long createdBy) {
            this.contentId = contentId;
            this.channelId = channelId;
            this.revisionNo = revisionNo;
            this.channelConfigId = channelConfigId;
            this.baseRunId = baseRunId;
            this.workingTitle = workingTitle;
            this.brief = brief;
            this.language = language;
            this.targetDurationSeconds = targetDurationSeconds;
            this.settingsJson = settingsJson;
            this.revisionHash = revisionHash;
            this.hashVersion = hashVersion;
            this.changeReason = changeReason;
            this.createdBy = createdBy;
        }
    }

    // ---------------------------------------------------------------- content
    @Insert("""
            INSERT INTO content (channel_id, topic_id, content_type, title, status, version, created_by, created_at,
                   updated_at)
            VALUES (#{channelId}, #{topicId}, #{contentType}, #{title}, 'DRAFT', 0, #{createdBy}, UTC_TIMESTAMP(6),
                   UTC_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertContent(NewContent content);

    /** 생성 TX 안에서 최초 revision 포인터를 설정한다(이후 NULL 복귀는 트리거가 거부). */
    @Update("""
            UPDATE content SET current_revision_id = #{revisionId}, title = #{title}
             WHERE id = #{id} AND current_revision_id IS NULL AND status = 'DRAFT'
            """)
    int setInitialRevision(@Param("id") long id, @Param("revisionId") long revisionId, @Param("title") String title);

    /** 새 revision으로 현재 포인터·제목 복제본을 원자 갱신한다. DRAFT·활성 run 없음·version 일치가 조건. */
    @Update("""
            UPDATE content SET current_revision_id = #{revisionId}, title = #{title}, version = version + 1,
                   updated_at = UTC_TIMESTAMP(6)
             WHERE id = #{id} AND version = #{version} AND status = 'DRAFT' AND active_run_id IS NULL
            """)
    int setCurrentRevision(@Param("id") long id, @Param("version") long version, @Param("revisionId") long revisionId,
            @Param("title") String title);

    @Update("""
            UPDATE content SET status = 'ARCHIVED', archived_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6),
                   version = version + 1
             WHERE id = #{id} AND version = #{version} AND status = 'DRAFT' AND active_run_id IS NULL
            """)
    int archive(@Param("id") long id, @Param("version") long version);

    @Select("SELECT " + COLUMNS + " FROM content WHERE id = #{id}")
    Content findById(long id);

    @Select("SELECT " + COLUMNS + " FROM content WHERE id = #{id} FOR UPDATE")
    Content lockById(long id);

    @Select("""
            <script>
            SELECT\s""" + COLUMNS + """
             FROM content
            <where>
              <if test="channelId != null">channel_id = #{channelId}</if>
              <if test="topicId != null">AND topic_id = #{topicId}</if>
              <if test="contentType != null">AND content_type = #{contentType}</if>
              <if test="status != null">AND status = #{status}</if>
              <if test="q != null">AND title LIKE CONCAT('%', #{q}, '%') ESCAPE '!'</if>
            </where>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Content> list(@Param("channelId") Long channelId, @Param("topicId") Long topicId,
            @Param("contentType") String contentType, @Param("status") String status, @Param("q") String q,
            @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM content
            <where>
              <if test="channelId != null">channel_id = #{channelId}</if>
              <if test="topicId != null">AND topic_id = #{topicId}</if>
              <if test="contentType != null">AND content_type = #{contentType}</if>
              <if test="status != null">AND status = #{status}</if>
              <if test="q != null">AND title LIKE CONCAT('%', #{q}, '%') ESCAPE '!'</if>
            </where>
            </script>
            """)
    long count(@Param("channelId") Long channelId, @Param("topicId") Long topicId,
            @Param("contentType") String contentType, @Param("status") String status, @Param("q") String q);

    // ---------------------------------------------------------------- revision
    @Insert("""
            INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, base_run_id,
                   working_title, brief, language, target_duration_seconds, settings_json, revision_hash, hash_version,
                   change_reason, created_by, created_at)
            VALUES (#{contentId}, #{channelId}, #{revisionNo}, #{channelConfigId}, #{baseRunId}, #{workingTitle},
                   #{brief}, #{language}, #{targetDurationSeconds}, #{settingsJson}, #{revisionHash}, #{hashVersion},
                   #{changeReason}, #{createdBy}, UTC_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertRevision(NewRevision revision);

    @Select("SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE id = #{id}")
    ContentRevision findRevisionById(long id);

    @Select("""
            <script>
            SELECT\s""" + REVISION_COLUMNS + """
             FROM content_revision WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ContentRevision> findRevisionsByIds(@Param("ids") Collection<Long> ids);

    @Select("SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE content_id = #{contentId}"
            + " AND revision_no = #{revisionNo}")
    ContentRevision findRevision(@Param("contentId") long contentId, @Param("revisionNo") int revisionNo);

    @Select("SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE content_id = #{contentId}"
            + " ORDER BY revision_no DESC LIMIT #{limit} OFFSET #{offset}")
    List<ContentRevision> listRevisions(@Param("contentId") long contentId, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM content_revision WHERE content_id = #{contentId}")
    long countRevisions(long contentId);

    // ---------------------------------------------------------------- run (읽기 전용, 실행 연결은 T03-C)
    /** 콘텐츠의 비종료(QUEUED/RUNNING) run ID. uk_content_run_nonterminal로 최대 1개. 잠금 없는 읽기. */
    @Select("SELECT id FROM content_run WHERE nonterminal_content_id = #{contentId}")
    Long nonterminalRunId(long contentId);

    @Select("SELECT status FROM content_run WHERE id = #{runId} AND content_id = #{contentId}")
    String runStatus(@Param("contentId") long contentId, @Param("runId") long runId);
}
