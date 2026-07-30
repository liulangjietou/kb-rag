package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.DocAcl;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data access for t_kb_doc_acl.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface DocAclMapper extends BaseMapper<DocAcl> {

    /**
     * Physically removes every grant of one document, so a rebind starts from an empty set.
     *
     * @param documentId document business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_doc_acl WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") String documentId);

    /**
     * Physically removes every grant held through one role, used when the role itself is deleted.
     *
     * <p>A grant pointing at a vanished role can never match any caller again, so leaving it behind
     * would not open anything - but it would render in the visibility editor as a role nobody can
     * resolve, which reads exactly like a corrupted grant.
     *
     * @param roleId role business id
     * @return deleted row count
     */
    @Delete("DELETE FROM t_kb_doc_acl WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);
}
