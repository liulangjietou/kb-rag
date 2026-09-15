package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.kbrag.domain.model.AppCorpusDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 为版本对比读取已通过当前文档 ACL 裁剪的版本集合。 */
@Mapper
public interface AppCorpusDocumentMapper {
    /** 同时约束版本、文档所属知识库和租户，损坏的冻结 ID 不能跨根解析。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT v.version_id, d.doc_id, d.file_name, v.version
            FROM t_kb_document_version v
            JOIN t_kb_document d ON d.doc_id = v.doc_id AND d.deleted = 0
            JOIN t_kb_knowledge_base k ON k.kb_id = d.kb_id AND k.deleted = 0
            WHERE v.deleted = 0 AND k.tenant_id = #{tenantId} AND k.kb_id = #{kbId}
              AND v.version_id IN
              <foreach collection="versionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<AppCorpusDocument> selectVersions(@Param("tenantId") String tenantId,
                                          @Param("kbId") String kbId,
                                          @Param("versionIds") List<String> versionIds);
}
