package io.kbrag.app.extsource;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.ExtSourceItem;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.ExtSourceItemStatus;
import io.kbrag.domain.enums.ExtSourceSyncStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.ExtSourceItemMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ConnectorRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 失败记录不能因为远端 ETag 与上次成功版本相同，就在未入库时变成成功。 */
class ExtSourceFailedRetryTest {
    private final ExtSourceMapper sources = mock(ExtSourceMapper.class);
    private final ExtSourceItemMapper items = mock(ExtSourceItemMapper.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final ExternalConnector connector = mock(ExternalConnector.class);
    private final ExtSource source = new ExtSource();
    private final KbProperties properties = new KbProperties();
    private ExtSourceService service;
    private ExtSourceItem failed;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(ExtSource.class, ExtSourceItem.class, Document.class);
        var bases = mock(KnowledgeBaseService.class);
        var router = mock(ConnectorRouter.class);
        when(router.resolve("s3")).thenReturn(connector);
        var base = new KnowledgeBase(); base.setKbId("kb_one"); base.setTenantId("tenant_one");
        when(bases.find("kb_one")).thenReturn(base);
        service = new ExtSourceService(sources, items, mock(DocumentMapper.class), documents, bases, router,
                mock(BizIdGenerator.class), properties, mock(KbMetrics.class));
        source.setId(1L); source.setSourceId("source_one"); source.setKbId("kb_one"); source.setSourceType("s3");
        when(sources.selectOne(any())).thenReturn(source);
        failed = item("failed.md", ExtSourceItemStatus.FAILED);
        when(items.selectOne(any())).thenReturn(failed);
        when(connector.testConnection(any())).thenReturn(HealthStatus.up("ok"));
        when(connector.listObjects(any())).thenReturn(List.of(object("failed.md")));
        when(connector.fetchObject(any(), anyString())).thenReturn(new byte[]{1});
        var document = new Document(); document.setDocId("doc_one");
        when(documents.upload(eq("kb_one"), anyString(), any()))
                .thenReturn(new UploadOutcome(document, "dv_one", "v1", true, null));
    }

    @Test
    void shouldRetryFailedObjectEvenWhenRemoteEtagMatchesLastIngestedVersion() {
        service.syncSource(source);
        verify(connector).fetchObject(any(), eq("failed.md"));
        verify(documents).upload(eq("kb_one"), anyString(), any());
        assertEquals(ExtSourceItemStatus.SUCCESS, failed.getLastStatus());
    }

    @Test
    void shouldRetryOnlyFailedObjectsAndPreserveFullScanHealth() {
        var previous = LocalDateTime.of(2026, 9, 1, 12, 0);
        source.setLastSuccessAt(previous); source.setLastSyncAt(previous);
        source.setLastSyncStatus(ExtSourceSyncStatus.PARTIAL); source.setLastError("previous full scan");
        when(items.selectList(any())).thenReturn(List.of(failed));
        when(connector.listObjects(any())).thenReturn(List.of(object("healthy.md"), object("new.md"), object("failed.md")));

        service.retryFailedSource("source_one");

        verify(connector, times(1)).fetchObject(any(), eq("failed.md"));
        verify(documents, times(1)).upload(eq("kb_one"), anyString(), any());
        assertEquals(ExtSourceItemStatus.SUCCESS, failed.getLastStatus());
        assertEquals(previous, source.getLastSuccessAt()); assertEquals(previous, source.getLastSyncAt());
        assertEquals(ExtSourceSyncStatus.PARTIAL, source.getLastSyncStatus());
        assertEquals("previous full scan", source.getLastError());
        verify(sources, never()).updateById(any(ExtSource.class));
    }

    @Test
    void shouldNotCallTheConnectorWhenThereAreNoFailedItems() {
        when(items.selectList(any())).thenReturn(List.of());
        service.retryFailedSource("source_one");
        verifyNoInteractions(connector, documents);
    }

    @Test
    void shouldKeepUnseenItemsFailedForTruncatedListingsAndSkipOnlyProvenMissingOnes() {
        when(items.selectList(any())).thenReturn(List.of(failed));
        properties.getExtSource().setMaxObjectsPerSource(1);
        when(connector.listObjects(any())).thenReturn(List.of(object("healthy.md"), object("unseen.md")));
        service.retryFailedSource("source_one");
        assertEquals(ExtSourceItemStatus.FAILED, failed.getLastStatus());
        assertTrue(failed.getLastError().contains("扫描上限"));
        when(connector.listObjects(any())).thenReturn(List.of());
        service.retryFailedSource("source_one");
        assertEquals(ExtSourceItemStatus.SKIPPED, failed.getLastStatus());
        assertTrue(failed.getLastError().contains("文档保持不变"));
        verifyNoInteractions(documents);
    }

    @Test
    void shouldPersistConnectionFailureAndReleaseTheSourceForAnotherAttempt() {
        when(items.selectList(any())).thenReturn(List.of(failed));
        when(connector.testConnection(any())).thenReturn(HealthStatus.down("temporary unavailable"));
        service.retryFailedSource("source_one");
        assertEquals(ExtSourceItemStatus.FAILED, failed.getLastStatus());
        assertEquals("temporary unavailable", failed.getLastError()); assertNotNull(failed.getLastSyncAt());
        verify(connector, never()).listObjects(any());
        when(connector.testConnection(any())).thenReturn(HealthStatus.up("ok"));
        service.retryFailedSource("source_one");
        assertEquals(ExtSourceItemStatus.SUCCESS, failed.getLastStatus());
        assertNull(failed.getLastError());
    }

    @Test
    void shouldKeepFullSyncFromOverlappingAnActiveRetry() throws Exception {
        when(items.selectList(any())).thenReturn(List.of(failed));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(connector.fetchObject(any(), anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return new byte[]{1};
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> service.retryFailedSource("source_one"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.syncSource(source);
            verify(connector, times(1)).listObjects(any());
            release.countDown(); future.get(5, TimeUnit.SECONDS);
            assertEquals(ExtSourceItemStatus.SUCCESS, failed.getLastStatus());
        } finally {
            release.countDown(); executor.shutdownNow();
        }
    }

    private ExtSourceItem item(String key, ExtSourceItemStatus status) {
        var item = new ExtSourceItem(); item.setId(2L); item.setSourceId("source_one");
        item.setObjectKey(key); item.setObjectKeyHash(HashUtil.sha256Hex(key)); item.setEtag("v1");
        item.setLastStatus(status); return item;
    }

    private ExternalConnector.RemoteObject object(String key) {
        return new ExternalConnector.RemoteObject(key, null, "v1", 1, null);
    }
}
