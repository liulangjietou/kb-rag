package io.kbrag.app.kb;

import io.kbrag.app.index.EngineChunkCleaner;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.FusionMode;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.GraphStore;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.GraphFusionPolicy;
import io.kbrag.domain.service.VersionFingerprintFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the knowledge base side of the graph switch: the mutual exclusion with weighted fusion at the
 * one place it is enforced, and the deliberate absence of any deletion when the switch goes off.
 *
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseGraphSwitchTest {

    private static final String KB_ID = "kb_test";

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private GraphStore graphStore;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        graphStore = mock(GraphStore.class);
        service = new KnowledgeBaseService(knowledgeBaseMapper, mock(DocumentMapper.class),
                mock(DocumentVersionMapper.class), mock(ChunkMapper.class), mock(BizIdGenerator.class),
                mock(IndexAliasManager.class), mock(EngineChunkCleaner.class),
                mock(VersionFingerprintFactory.class), mock(VisionProvider.class), mock(ChatProvider.class),
                new GraphFusionPolicy(), graphStore, new KbProperties());
    }

    @Test
    void shouldRejectTurningTheGraphOnWhileTheBaseFusesByWeight() {
        givenStoredRetrievalConfig(null, FusionMode.WEIGHTED.code());

        BizException exception = assertThrows(BizException.class,
                () -> service.updateGraphEnabled(KB_ID, true));

        assertEquals(ErrorCode.INVALID_PARAM, exception.getErrorCode());
        verify(knowledgeBaseMapper, never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    void shouldAcceptTurningTheGraphOnWhenTheBaseFusesByReciprocalRank() {
        givenStoredRetrievalConfig(null, FusionMode.RRF.code());

        KbRetrievalConfig stored = service.updateGraphEnabled(KB_ID, true);

        assertTrue(stored.graphEnabled());
        verify(knowledgeBaseMapper).updateById(any(KnowledgeBase.class));
    }

    @Test
    void shouldAllowTurningTheGraphOffOnAWeightedBase() {
        givenStoredRetrievalConfig(true, FusionMode.WEIGHTED.code());

        KbRetrievalConfig stored = service.updateGraphEnabled(KB_ID, false);

        assertFalse(stored.graphEnabled());
    }

    @Test
    void shouldNotDeleteAnyGraphDataWhenTheSwitchGoesOff() {
        givenStoredRetrievalConfig(true, FusionMode.RRF.code());

        service.updateGraphEnabled(KB_ID, false);

        // Re-enabling must not cost a full re-extraction, so the switch never destroys the graph; only a
        // document or knowledge base deletion does.
        verify(graphStore, never()).deleteKb(KB_ID);
    }

    @Test
    void shouldReportTheSwitchOfABaseThatNeverConfiguredRetrieval() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase);

        assertFalse(service.graphEnabled(KB_ID));
        assertFalse(service.retrievalConfigOf(knowledgeBase).graphEnabled());
    }

    @Test
    void shouldRejectAGraphEnabledRetrievalConfigWrittenThroughTheIndexConfigEndpoint() {
        givenStoredRetrievalConfig(null, FusionMode.RRF.code());
        KbRetrievalConfig incoming = new KbRetrievalConfig();
        incoming.setGraphEnabled(true);
        incoming.setFusionMode(FusionMode.WEIGHTED.code());

        BizException exception = assertThrows(BizException.class,
                () -> service.updateIndexConfig(KB_ID, new KbIndexConfig(), incoming));

        assertEquals(ErrorCode.INVALID_PARAM, exception.getErrorCode());
    }

    private void givenStoredRetrievalConfig(Boolean graphEnabled, String fusionMode) {
        KbRetrievalConfig config = new KbRetrievalConfig();
        config.setGraphEnabled(graphEnabled);
        config.setFusionMode(fusionMode);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setKbId(KB_ID);
        knowledgeBase.setRetrievalConfig(JsonUtil.toJson(config));
        when(knowledgeBaseMapper.selectOne(any())).thenReturn(knowledgeBase);
    }
}
