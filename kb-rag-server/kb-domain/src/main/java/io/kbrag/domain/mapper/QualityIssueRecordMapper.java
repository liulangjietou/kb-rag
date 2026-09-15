package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.QualityIssueRecord;
import org.apache.ibatis.annotations.Mapper;

/** 仅在问题授权后按 issue_id 读取或追加。 */
@Mapper
public interface QualityIssueRecordMapper extends BaseMapper<QualityIssueRecord> { }
