package com.aistudio.topic;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface TopicMapper {

    String COLUMNS = "id, channel_id, title, normalized_title, normalized_hash, normalization_version, note,"
            + " created_by, created_at";

    final class NewTopic {
        public Long id;
        public final long channelId;
        public final String title;
        public final String normalizedTitle;
        public final String normalizedHash;
        public final int normalizationVersion;
        public final String note;
        public final long createdBy;

        NewTopic(long channelId, String title, String normalizedTitle, String normalizedHash, int normalizationVersion,
                String note, long createdBy) {
            this.channelId = channelId;
            this.title = title;
            this.normalizedTitle = normalizedTitle;
            this.normalizedHash = normalizedHash;
            this.normalizationVersion = normalizationVersion;
            this.note = note;
            this.createdBy = createdBy;
        }
    }

    @Insert("""
            INSERT INTO topic (channel_id, title, normalized_title, normalized_hash, normalization_version, note,
                   created_by, created_at)
            VALUES (#{channelId}, #{title}, #{normalizedTitle}, #{normalizedHash}, #{normalizationVersion}, #{note},
                   #{createdBy}, UTC_TIMESTAMP(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(NewTopic topic);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE id = #{id}")
    Topic findById(long id);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE id = #{id} AND channel_id = #{channelId}")
    Topic findInChannel(@Param("id") long id, @Param("channelId") long channelId);

    @Select("SELECT " + COLUMNS + " FROM topic WHERE channel_id = #{channelId} AND normalized_hash = #{hash}")
    Topic findByHash(@Param("channelId") long channelId, @Param("hash") String hash);

    /** q는 정규화 후 LIKE 이스케이프('!')된 값. */
    @Select("""
            <script>
            SELECT\s""" + COLUMNS + """
             FROM topic WHERE channel_id = #{channelId}
            <if test="q != null"> AND normalized_title LIKE CONCAT('%', #{q}, '%') ESCAPE '!'</if>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Topic> list(@Param("channelId") long channelId, @Param("q") String q, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM topic WHERE channel_id = #{channelId}
            <if test="q != null"> AND normalized_title LIKE CONCAT('%', #{q}, '%') ESCAPE '!'</if>
            </script>
            """)
    long count(@Param("channelId") long channelId, @Param("q") String q);
}
