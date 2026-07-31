package io.kbrag.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kbrag.domain.entity.MemoryNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * Data access for t_kb_memory_node.
 *
 * <p>Nodes are soft deleted through the base mapper: {@code uk_node_id} values are random and never
 * reused, so a dead row can never hold a key hostage the way a profile row could.
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface MemoryNodeMapper extends BaseMapper<MemoryNode> {
}
