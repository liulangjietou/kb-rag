package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import org.apache.ibatis.annotations.Mapper;

/** 查询前必须完成所属知识库的租户与资源范围裁剪。 */
@Mapper
public interface KnowledgeQualityIssueMapper extends BaseMapper<KnowledgeQualityIssue> { }
