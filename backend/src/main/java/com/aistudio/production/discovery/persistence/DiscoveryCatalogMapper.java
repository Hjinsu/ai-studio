package com.aistudio.production.discovery.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;

@Mapper
interface DiscoveryCatalogMapper {
    String CONTENT = """
            SELECT CAST(t.id AS CHAR) AS topic_id, CAST(c.id AS CHAR) AS content_id,
                   CAST(r.id AS CHAR) AS revision_id, t.normalized_hash AS normalized_topic_hash,
                   r.working_title AS title, r.brief
            FROM content c JOIN topic t ON t.id = c.topic_id
            JOIN content_revision r ON r.id = c.current_revision_id
            WHERE c.channel_id = #{channelId}
            """;

    @Select("""
            <script>
            SELECT CAST(id AS CHAR) AS topic_id, NULL AS content_id, NULL AS revision_id,
                   normalized_hash AS normalized_topic_hash, title, NULL AS brief
            FROM topic WHERE channel_id = #{channelId} AND normalized_hash IN
            <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach>
            ORDER BY id LOCK IN SHARE MODE
            </script>
            """)
    List<CatalogEntry> exact(long channelId, List<String> hashes);

    @Select(CONTENT + " ORDER BY c.updated_at DESC, c.id DESC LIMIT 100 LOCK IN SHARE MODE")
    List<CatalogEntry> recent(long channelId);

    @Select("<script>" + CONTENT + """
            AND (
                <foreach collection="terms" item="term" separator=" OR ">
                    INSTR(LOWER(CONCAT_WS(' ', r.working_title, r.brief)), #{term}) &gt; 0
                </foreach>
            )
            ORDER BY c.updated_at DESC, c.id DESC LIMIT 100 LOCK IN SHARE MODE
            </script>
            """)
    List<CatalogEntry> related(long channelId, List<String> terms);
}
