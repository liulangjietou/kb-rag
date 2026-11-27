package io.kbrag.app.kb;

import io.kbrag.app.auth.RoleService;
import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.app.index.IndexCleanupService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphFusionPolicy;
import io.kbrag.domain.service.VersionFingerprintFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖服务层对切分策略的两道把关：新建知识库的默认配置，以及按扩展名覆盖的取值合法性与模型可用性。
 *
 * <p>二者此前都只看库级 {@code split_strategy}：默认配置在部署开启父子分片时仍写入语义切分与
 * table_row 覆盖，形成"指纹记一种、实际跑定长"的配置；扩展名覆盖里的未知码或缺模型的策略则能直接落库。
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseSplitStrategyTest {

    private static final String KB_ID = "kb_test";

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private ChatProvider chatProvider;
    private EmbeddingProvider embeddingProvider;
    private KbProperties properties;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        chatProvider = mock(ChatProvider.class);
        embeddingProvider = mock(EmbeddingProvider.class);
        properties = new KbProperties();
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.knowledgeBaseId()).thenReturn(KB_ID);
        service = new KnowledgeBaseService(knowledgeBaseMapper, mock(DocumentMapper.class),
                mock(DocumentVersionMapper.class), mock(ChunkMapper.class), bizIdGenerator,
                mock(IndexAliasManager.class), mock(EngineChunkCleaner.class), mock(IndexCleanupService.class),
                new VersionFingerprintFactory(), mock(VisionProvider.class), chatProvider, embeddingProvider,
                new GraphFusionPolicy(), mock(GraphStore.class), mock(RoleService.class), properties);
    }

    @Test
    void shouldCreateAFixedLengthBaseWithoutOverridesWhenTheDeploymentDefaultsToTwoLevels() {
        // 两级切分只会做定长：默认值若仍写语义切分与 table_row 覆盖，新库一建好就是"配置与实际不符"
        properties.getSplit().setParentChildEnabled(true);
        when(embeddingProvider.isConfigured()).thenReturn(true);

        KbIndexConfig stored = createAndCaptureConfig();

        assertTrue(stored.parentChildEnabled());
        assertEquals("fixed_length", stored.getSplitStrategy());
        assertTrue(stored.splitStrategyByExtOrEmpty().isEmpty());
    }

    @Test
    void shouldKeepTheSemanticAndTableDefaultsOnASingleLevelBase() {
        when(embeddingProvider.isConfigured()).thenReturn(true);

        KbIndexConfig stored = createAndCaptureConfig();

        assertEquals("embedding_semantic", stored.getSplitStrategy());
        assertEquals(Map.of("xlsx", "table_row", "xls", "table_row", "csv", "table_row"),
                stored.getSplitStrategyByExt());
    }

    @Test
    void shouldRejectAnUnknownCodeInTheExtensionOverrides() {
        // 未知码会在切分路由里静默回落定长，而控制台仍显示为"已配置"
        KbIndexConfig config = fixedLengthWith(Map.of("md", "nonsense"));

        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> service.updateIndexConfig(KB_ID, config, null)).getErrorCode());
        verify(knowledgeBaseMapper, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void shouldRejectAnLlmSemanticOverrideWithoutAChatModel() {
        when(chatProvider.isConfigured()).thenReturn(false);
        KbIndexConfig config = fixedLengthWith(Map.of("md", "llm_semantic"));

        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> service.updateIndexConfig(KB_ID, config, null)).getErrorCode());
    }

    @Test
    void shouldRejectAnEmbeddingSemanticOverrideWithoutAnEmbeddingModel() {
        when(embeddingProvider.isConfigured()).thenReturn(false);
        KbIndexConfig config = fixedLengthWith(Map.of("md", "embedding_semantic"));

        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(BizException.class,
                () -> service.updateIndexConfig(KB_ID, config, null)).getErrorCode());
    }

    private KbIndexConfig createAndCaptureConfig() {
        service.create("test-kb", null);
        ArgumentCaptor<KnowledgeBase> inserted = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(knowledgeBaseMapper).insert(inserted.capture());
        return JsonUtil.parse(inserted.getValue().getIndexConfig(), KbIndexConfig.class);
    }

    private KbIndexConfig fixedLengthWith(Map<String, String> overrides) {
        KbIndexConfig config = new KbIndexConfig();
        config.setSplitStrategy("fixed_length");
        config.setChunkMaxTokens(600);
        config.setChunkOverlap(100);
        config.setSplitStrategyByExt(overrides);
        return config;
    }
}
