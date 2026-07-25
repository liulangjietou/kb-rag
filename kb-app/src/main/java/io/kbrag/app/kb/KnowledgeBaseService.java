package io.kbrag.app.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knowledge base lifecycle.
 *
 * <p>Creating a knowledge base also materialises its physical indices and aliases, so an upload never
 * has to wait for a lazy first time index creation and the console can show the index state panel
 * immediately.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final String CONFIG_SPLIT_STRATEGY = "split_strategy";
    private static final String CONFIG_MAX_TOKENS = "max_tokens";
    private static final String CONFIG_OVERLAP_TOKENS = "overlap_tokens";
    private static final String CONFIG_EMBEDDING_MODEL = "embedding_model";
    private static final String DEFAULT_SPLIT_STRATEGY = "fixed_length";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final BizIdGenerator bizIdGenerator;
    private final IndexAliasManager indexAliasManager;
    private final KbProperties properties;

    /**
     * Creates a knowledge base and its indices.
     *
     * @param name        display name
     * @param description free text description
     * @return created aggregate
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase create(String name, String description) {
        if (knowledgeBaseMapper.exists(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getName, name))) {
            throw BizException.invalidParam("knowledge base name already exists");
        }
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(bizIdGenerator.knowledgeBaseId());
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        String indexConfig = JsonUtil.toJson(defaultIndexConfig());
        knowledgeBase.setIndexConfig(indexConfig);
        knowledgeBase.setCurrentConfigFingerprint(HashUtil.sha256Hex(indexConfig));
        knowledgeBaseMapper.insert(knowledgeBase);
        indexAliasManager.ensureIndexes(knowledgeBase.getKbId());
        log.info("knowledge base created, kbId={}, name={}", knowledgeBase.getKbId(), name);
        return knowledgeBase;
    }

    /**
     * Lists every knowledge base, newest first.
     *
     * @return knowledge bases
     */
    public List<KnowledgeBase> list() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .orderByDesc(KnowledgeBase::getId));
    }

    /**
     * Loads a knowledge base or fails.
     *
     * @param kbId business id
     * @return knowledge base
     */
    public KnowledgeBase require(String kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getKbId, kbId)
                .last("limit 1"));
        if (knowledgeBase == null) {
            throw BizException.notFound("knowledge base not found");
        }
        return knowledgeBase;
    }

    /**
     * Soft deletes a knowledge base and its documents.
     *
     * <p>Physical indices are left in place and marked for the cleanup task instead of being dropped
     * inline, so a mis-click never destroys data that MySQL can still rebuild from.
     *
     * @param kbId business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId) {
        KnowledgeBase knowledgeBase = require(kbId);
        documentMapper.delete(new LambdaQueryWrapper<Document>().eq(Document::getKbId, kbId));
        knowledgeBaseMapper.deleteById(knowledgeBase.getId());
        log.info("knowledge base deleted, kbId={}", kbId);
        // TODO(M2): schedule a CLEANUP task that drops the physical indices of this knowledge base.
    }

    private Map<String, Object> defaultIndexConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(CONFIG_SPLIT_STRATEGY, DEFAULT_SPLIT_STRATEGY);
        config.put(CONFIG_MAX_TOKENS, properties.getSplit().getMaxTokens());
        config.put(CONFIG_OVERLAP_TOKENS, properties.getSplit().getOverlapTokens());
        config.put(CONFIG_EMBEDDING_MODEL, properties.getEmbedding().getModel());
        return config;
    }
}
