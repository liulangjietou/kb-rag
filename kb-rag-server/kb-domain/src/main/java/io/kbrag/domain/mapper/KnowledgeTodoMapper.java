package io.kbrag.domain.mapper;

import io.kbrag.domain.model.KnowledgeTodoCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 只接收服务层已授权的知识库根，聚合前执行范围过滤。 */
@Mapper
public interface KnowledgeTodoMapper {
    /** 不读取逐文档或来源配置，回收站与逻辑删除记录不参与待办。 */
    @Select("""
            <script>
            SELECT kb_id, 'PENDING_CONFIRM' AS kind, COUNT(*) AS total FROM t_kb_document
            WHERE deleted = 0 AND trashed = 0 AND process_status = 'PENDING_CONFIRM'
              AND kb_id IN <foreach collection="kbIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY kb_id
            UNION ALL
            SELECT kb_id, 'PENDING_REVIEW' AS kind, COUNT(*) AS total FROM t_kb_document
            WHERE deleted = 0 AND trashed = 0 AND publish_status = 'PENDING_REVIEW'
              AND kb_id IN <foreach collection="kbIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY kb_id
            UNION ALL
            SELECT kb_id, 'WEB_SOURCE_FAILED' AS kind, COUNT(*) AS total FROM t_kb_web_source
            WHERE deleted = 0 AND last_fetch_status = 'FAILED'
              AND kb_id IN <foreach collection="kbIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY kb_id
            UNION ALL
            SELECT kb_id, 'EXT_SOURCE_ATTENTION' AS kind, COUNT(*) AS total FROM t_kb_ext_source
            WHERE deleted = 0 AND last_sync_status IN ('FAILED','PARTIAL')
              AND kb_id IN <foreach collection="kbIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY kb_id
            </script>
            """)
    List<KnowledgeTodoCount> counts(@Param("kbIds") List<String> kbIds);
}
