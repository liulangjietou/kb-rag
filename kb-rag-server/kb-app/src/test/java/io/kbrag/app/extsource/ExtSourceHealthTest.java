package io.kbrag.app.extsource;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.*;
import io.kbrag.domain.enums.ExtSourceSyncStatus;
import io.kbrag.domain.mapper.*;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ConnectorRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 部分失败可以伴随真实内容变更，但不能推进完整同步成功时间。 */
class ExtSourceHealthTest {
    private final ExtSourceMapper mapper = mock(ExtSourceMapper.class);
    private final ExtSourceItemMapper items = mock(ExtSourceItemMapper.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final ExternalConnector connector = mock(ExternalConnector.class);
    private final ExtSource source = new ExtSource();
    private final KbProperties properties = new KbProperties();
    private ExtSourceService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(ExtSource.class, ExtSourceItem.class);
        KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
        ConnectorRouter router = mock(ConnectorRouter.class);
        when(router.resolve("s3")).thenReturn(connector);
        KnowledgeBase base = new KnowledgeBase(); base.setKbId("kb_one"); base.setTenantId("tenant_one");
        when(bases.find("kb_one")).thenReturn(base);
        service = new ExtSourceService(mapper, items, mock(DocumentMapper.class), documents, bases, router,
                mock(BizIdGenerator.class), properties, mock(KbMetrics.class));
        source.setId(1L); source.setSourceId("source_one"); source.setKbId("kb_one"); source.setSourceType("s3");
        when(connector.testConnection(any())).thenReturn(HealthStatus.up("ok"));
    }

    @Test
    void shouldKeepActualChangeOnPartialAndAdvanceSuccessOnlyOnCompletePass() {
        var oldSuccess = LocalDateTime.of(2026, 9, 1, 12, 0);
        source.setLastSuccessAt(oldSuccess);
        when(connector.listObjects(any())).thenReturn(List.of(object("good.md"), object("bad.md")));
        when(connector.fetchObject(any(), eq("good.md"))).thenReturn(new byte[] {1});
        when(connector.fetchObject(any(), eq("bad.md"))).thenThrow(new IllegalStateException("timeout"));
        when(documents.upload(eq("kb_one"), anyString(), any())).thenReturn(outcome(false));

        service.syncSource(source);

        assertEquals(ExtSourceSyncStatus.PARTIAL, source.getLastSyncStatus());
        assertEquals(oldSuccess, source.getLastSuccessAt());
        var change = source.getLastContentChangeAt();
        assertNotNull(change);
        verify(mapper).advanceHealthTimes(1L, oldSuccess, change);
        when(connector.listObjects(any())).thenReturn(List.of(object("good.md")));
        when(documents.upload(eq("kb_one"), anyString(), any())).thenReturn(outcome(true));
        service.syncSource(source);
        assertEquals(ExtSourceSyncStatus.SUCCESS, source.getLastSyncStatus());
        assertTrue(source.getLastSuccessAt().isAfter(oldSuccess));
        assertEquals(change, source.getLastContentChangeAt());
    }

    @Test
    void shouldNotInventSuccessOrChangeOnProbeFailureOrTruncatedEmptyImport() {
        when(connector.testConnection(any())).thenReturn(HealthStatus.down("unavailable"));
        service.syncSource(source);
        assertEquals(ExtSourceSyncStatus.FAILED, source.getLastSyncStatus());
        assertNull(source.getLastSuccessAt()); assertNull(source.getLastContentChangeAt());
        verify(connector, never()).listObjects(any());
        verifyNoInteractions(documents);
        properties.getExtSource().setMaxObjectsPerSource(1);
        when(connector.testConnection(any())).thenReturn(HealthStatus.up("ok"));
        when(connector.listObjects(any())).thenReturn(List.of(object("first.unsupported"), object("second.unsupported")));
        service.syncSource(source);
        assertEquals(ExtSourceSyncStatus.PARTIAL, source.getLastSyncStatus());
        assertNull(source.getLastSuccessAt()); assertNull(source.getLastContentChangeAt());
        verifyNoInteractions(documents);
    }

    private ExternalConnector.RemoteObject object(String key) {
        return new ExternalConnector.RemoteObject(key, null, "v1", 1, null);
    }

    private UploadOutcome outcome(boolean duplicated) {
        Document document = new Document(); document.setDocId("doc_one");
        return new UploadOutcome(document, "dv_one", "v1", duplicated, null);
    }
}
