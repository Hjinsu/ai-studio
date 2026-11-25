package com.aistudio.channel;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface ChannelMapper {

    String COLUMNS = "id, name, language, timezone, status, version, created_at, updated_at, archived_at";
    String CONFIG_COLUMNS = "id, channel_id, revision_no, default_content_type, shorts_min_seconds, shorts_max_seconds,"
            + " longform_min_seconds, longform_max_seconds, content_strategy, target_audience, narration_style,"
            + " visual_style, synthetic_disclosure_default, provider_options_json, change_note, created_by, created_at";

    final class NewChannel {
        public Long id;
        public final String name;
        public final String language;
        public final String timezone;
        public final Instant now;

        NewChannel(String name, String language, String timezone, Instant now) {
            this.name = name;
            this.language = language;
            this.timezone = timezone;
            this.now = now;
        }
    }

    @Insert("""
            INSERT INTO channel (name, language, timezone, status, version, created_at, updated_at)
            VALUES (#{name}, #{language}, #{timezone}, 'ACTIVE', 0, #{now}, #{now})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(NewChannel channel);

    @Select("SELECT " + COLUMNS + " FROM channel WHERE id = #{id}")
    Channel findById(long id);

    @Select("SELECT " + COLUMNS + " FROM channel WHERE id = #{id} FOR UPDATE")
    Channel lockById(long id);

    @Select("SELECT " + COLUMNS + " FROM channel WHERE id = #{id} LOCK IN SHARE MODE")
    Channel lockSharedById(long id);

    @Select("""
            <script>
            SELECT\s""" + COLUMNS + """
             FROM channel
            <where><if test="status != null">status = #{status}</if></where>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Channel> list(@Param("status") String status, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM channel <where><if test="status != null">status = #{status}</if></where>
            </script>
            """)
    long count(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM channel WHERE status = #{status}")
    long countByStatus(String status);

    @Update("""
            UPDATE channel SET name = #{name}, language = #{language}, timezone = #{timezone},
                   version = version + 1, updated_at = #{now}
             WHERE id = #{id} AND version = #{version}
            """)
    int updateInfo(@Param("id") long id, @Param("version") long version, @Param("name") String name,
            @Param("language") String language, @Param("timezone") String timezone, @Param("now") Instant now);

    @Update("""
            UPDATE channel SET status = #{status}, archived_at = #{archivedAt}, version = version + 1, updated_at = #{now}
             WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") long id, @Param("version") long version, @Param("status") String status,
            @Param("archivedAt") Instant archivedAt, @Param("now") Instant now);

    /** FD-16: 활성 run 포인터가 있는 콘텐츠 수(잠금 없는 읽기, channel X 획득 뒤 호출). */
    @Select("SELECT COUNT(*) FROM content WHERE channel_id = #{channelId} AND active_run_id IS NOT NULL")
    long countActiveRuns(long channelId);

    @Insert("""
            INSERT INTO channel_config (channel_id, revision_no, default_content_type, shorts_min_seconds,
                   shorts_max_seconds, longform_min_seconds, longform_max_seconds, content_strategy, target_audience,
                   narration_style, visual_style, synthetic_disclosure_default, provider_options_json, change_note,
                   created_by, created_at)
            VALUES (#{channelId}, #{revisionNo}, #{defaultContentType}, #{shortsMinSeconds}, #{shortsMaxSeconds},
                   #{longformMinSeconds}, #{longformMaxSeconds}, #{contentStrategy}, #{targetAudience},
                   #{narrationStyle}, #{visualStyle}, #{syntheticDisclosureDefault}, #{providerOptionsJson},
                   #{changeNote}, #{createdBy}, #{createdAt})
            """)
    void insertConfig(ChannelConfig config);

    @Select("SELECT COALESCE(MAX(revision_no), 0) FROM channel_config WHERE channel_id = #{channelId}")
    int maxConfigRevision(long channelId);

    @Select("SELECT " + CONFIG_COLUMNS + " FROM channel_config WHERE channel_id = #{channelId}"
            + " ORDER BY revision_no DESC LIMIT 1")
    ChannelConfig latestConfig(long channelId);

    @Select("SELECT " + CONFIG_COLUMNS + " FROM channel_config WHERE channel_id = #{channelId}"
            + " AND revision_no = #{revisionNo}")
    ChannelConfig findConfig(@Param("channelId") long channelId, @Param("revisionNo") int revisionNo);

    @Select("SELECT " + CONFIG_COLUMNS + " FROM channel_config WHERE channel_id = #{channelId}"
            + " ORDER BY revision_no DESC LIMIT #{limit} OFFSET #{offset}")
    List<ChannelConfig> listConfigs(@Param("channelId") long channelId, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM channel_config WHERE channel_id = #{channelId}")
    long countConfigs(long channelId);
}
