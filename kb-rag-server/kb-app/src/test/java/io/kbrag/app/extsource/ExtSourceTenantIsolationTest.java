package io.kbrag.app.extsource;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.ExtSourceItem;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.ExtSourceItemMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ConnectorRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the isolation of {@code t_kb_ext_source}, a subordinate table reached through {@code kb_id}.
 *
 * <p>Every case here describes the same shape: the registration row itself comes back for any caller,
 * because the statement that finds it carries no tenant clause and cannot - {@code source_id} exists
 * only in this table. What refuses the call is the fenced read of the owning base, modelled here as
 * {@code KnowledgeBaseService} answering empty. Before that read existed, one {@code sourceId} was
 * enough to overwrite another tenant's endpoint and access key, probe their object store with their
 * own credentials, hard delete the registration or page through its per object sync trail.
 *
 * @author owlzhangfq@gmail.com
 */
class ExtSourceTenantIsolationTest {

    private static final String KB_ID = "kb_alpha";
    private static final String SOURCE_ID = "extsrc_1";

    private ExtSourceMapper extSourceMapper;
    private ExtSourceItemMapper extSourceItemMapper;
    private KnowledgeBaseService knowledgeBaseService;
    private ConnectorRouter connectorRouter;
    private ExtSourceService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(ExtSource.class, ExtSourceItem.class);
        extSourceMapper = mock(ExtSourceMapper.class);
        extSourceItemMapper = mock(ExtSourceItemMapper.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        connectorRouter = mock(ConnectorRouter.class);
        service = new ExtSourceService(extSourceMapper, extSourceItemMapper, mock(DocumentMapper.class),
                mock(DocumentService.class), knowledgeBaseService, connectorRouter,
                mock(BizIdGenerator.class), new KbProperties(), mock(KbMetrics.class));
    }

    @Test
    void shouldTreatASourceOfAnotherTenantExactlyAsAnUnknownOne() {
        givenSourceRow();
        givenBaseOfAnotherTenant();

        assertNotFound(() -> service.update(SOURCE_ID, command()));
        assertNotFound(() -> service.testConnection(SOURCE_ID));
        assertNotFound(() -> service.remove(SOURCE_ID));
        assertNotFound(() -> service.listItems(SOURCE_ID, 1, 20));
        assertNotFound(() -> service.ensureExists(SOURCE_ID));
    }

    @Test
    void shouldNotWriteProbeOrDeleteBeforeTheBaseIsResolved() {
        givenSourceRow();
        givenBaseOfAnotherTenant();

        assertThrows(BizException.class, () -> service.update(SOURCE_ID, command()));
        assertThrows(BizException.class, () -> service.testConnection(SOURCE_ID));
        assertThrows(BizException.class, () -> service.remove(SOURCE_ID));

        // The point of resolving the root before anything else: no credential is overwritten, no
        // outbound probe leaves the process, and the hard delete - which has no undo - never runs.
        verify(extSourceMapper, never()).updateById(any(ExtSource.class));
        verify(extSourceMapper, never()).hardDeleteById(anyLong());
        verify(extSourceItemMapper, never()).hardDeleteBySourceId(anyString());
        verify(connectorRouter, never()).resolve(anyString());
    }

    @Test
    void shouldRefuseToListTheSourcesOfAnotherTenantsBase() {
        when(knowledgeBaseService.require(KB_ID))
                .thenThrow(BizException.notFound("knowledge base not found"));

        assertThrows(BizException.class, () -> service.list(KB_ID, 1, 20));
        verify(extSourceMapper, never()).selectPage(any(), any());
    }

    @Test
    void shouldRefuseToRegisterIntoAnotherTenantsBase() {
        when(knowledgeBaseService.require(KB_ID))
                .thenThrow(BizException.notFound("knowledge base not found"));

        assertThrows(BizException.class, () -> service.register(KB_ID, command()));
        verify(extSourceMapper, never()).insert(any(ExtSource.class));
    }

    private void givenSourceRow() {
        ExtSource source = new ExtSource();
        source.setId(1L);
        source.setSourceId(SOURCE_ID);
        source.setKbId(KB_ID);
        source.setSourceType("s3");
        source.setName("bucket-a");
        when(extSourceMapper.selectOne(any())).thenReturn(source);
    }

    /** The fence trimmed the base away: it belongs to another tenant. */
    private void givenBaseOfAnotherTenant() {
        when(knowledgeBaseService.find(anyString())).thenReturn(null);
    }

    private ExtSourceCommand command() {
        return new ExtSourceCommand("s3", "bucket-b", "https://oss.example.com", null, "bucket",
                null, "ak", "sk", Boolean.TRUE);
    }

    private void assertNotFound(Executable call) {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, call).getErrorCode());
    }
}
