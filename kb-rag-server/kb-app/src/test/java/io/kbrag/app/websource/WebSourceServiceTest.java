package io.kbrag.app.websource;

import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.WebSource;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.WebSourceMapper;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.UrlGuard;
import io.kbrag.domain.service.WebAuthException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the sync state machine of the M12 contract: everything a fetch produces must go through
 * the one upload chain, an unchanged page must write nothing, a trashed document must stay
 * untouched, a purged one must be rebuilt and rebound, and no sync failure may ever escape onto
 * the scheduler thread or the register call. M18 adds the credential hand-off and the one rule the
 * batch pass owns: after one authentication rejection of a site, no more requests go there.
 *
 * <p>V22 puts a tenant on both of those. A fetch may only spend the credentials of the tenant that
 * owns the base it feeds, and the rejection fence counts per (tenant, host) rather than per host -
 * pinned below, because the thread this runs on has no principal and therefore no fence of its own.
 *
 * @author owlzhangfq@gmail.com
 */
class WebSourceServiceTest {

    private static final String KB_ID = "kb_1";
    private static final String SOURCE_ID = "ws_1";
    private static final String TENANT_ID = "tnt_acme0000000001";
    private static final String OTHER_TENANT_ID = "tnt_globex000000001";
    private static final String URL = "https://example.com/docs/guide";
    private static final byte[] BODY = "<html><body>guide</body></html>".getBytes(StandardCharsets.UTF_8);

    private WebSourceMapper webSourceMapper;
    private DocumentMapper documentMapper;
    private DocumentService documentService;
    private KnowledgeBaseService knowledgeBaseService;
    private UrlGuard urlGuard;
    private WebPageFetcher webPageFetcher;
    private WebCredentialService webCredentialService;
    private BizIdGenerator bizIdGenerator;
    private KbProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private WebSourceService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(WebSource.class, Document.class);
        webSourceMapper = mock(WebSourceMapper.class);
        documentMapper = mock(DocumentMapper.class);
        documentService = mock(DocumentService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        urlGuard = mock(UrlGuard.class);
        webPageFetcher = mock(WebPageFetcher.class);
        webCredentialService = mock(WebCredentialService.class);
        bizIdGenerator = mock(BizIdGenerator.class);
        properties = new KbProperties();
        meterRegistry = new SimpleMeterRegistry();
        service = new WebSourceService(webSourceMapper, documentMapper, documentService,
                knowledgeBaseService, urlGuard, webPageFetcher, webCredentialService, bizIdGenerator,
                properties, new KbMetrics(meterRegistry));
        when(urlGuard.validate(anyString())).thenAnswer(invocation ->
                URI.create(invocation.getArgument(0, String.class)));
        when(bizIdGenerator.webSourceId()).thenReturn(SOURCE_ID);
        // Every registration in this class belongs to one base of one tenant; the batch tests that
        // need a second tenant override find() for their own kb id.
        when(knowledgeBaseService.require(KB_ID)).thenReturn(base(KB_ID, TENANT_ID));
        when(knowledgeBaseService.find(KB_ID)).thenReturn(base(KB_ID, TENANT_ID));
    }

    @Test
    void shouldRegisterAndRunTheFirstFetchThroughTheUploadChain() {
        when(webSourceMapper.selectCount(any())).thenReturn(0L);
        when(webPageFetcher.fetch(fetchOf(URL))).thenReturn(page());
        when(documentService.upload(eq(KB_ID), anyString(), eq(BODY))).thenReturn(outcome("doc_1"));

        WebSource source = service.register(KB_ID, URL, true, false);

        verify(webSourceMapper).insert(source);
        assertEquals(WebSourceFetchStatus.SUCCESS, source.getLastFetchStatus());
        assertEquals("doc_1", source.getDocId());
        assertEquals(HashUtil.sha256Hex(BODY), source.getLastContentHash());
        // The derived name is host plus path slug plus a hash prefix, under the upload extension.
        assertTrue(source.getFileName().startsWith("example.com-docs-guide-"));
        assertTrue(source.getFileName().endsWith(".html"));
        assertNull(source.getLastError());
        // The M13 sync counter rides the same record funnel, so a success must show up there too.
        assertEquals(1, meterRegistry.get("kb.websource.sync").tag("status", "success").counter().count());
    }

    @Test
    void shouldRejectADuplicateUrlWithinTheSameKnowledgeBase() {
        when(webSourceMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class, () -> service.register(KB_ID, URL, true, false));
        verify(webSourceMapper, never()).insert(any(WebSource.class));
    }

    @Test
    void shouldKeepTheRegistrationWhenTheFirstFetchFails() {
        // The operator registered the page, not the luck of this minute: the row must survive and
        // carry the failure for them to read.
        when(webSourceMapper.selectCount(any())).thenReturn(0L);
        when(webPageFetcher.fetch(fetchOf(URL))).thenThrow(new IllegalStateException("connection refused"));

        WebSource source = assertDoesNotThrow(() -> service.register(KB_ID, URL, true, false));

        verify(webSourceMapper).insert(source);
        assertEquals(WebSourceFetchStatus.FAILED, source.getLastFetchStatus());
        assertEquals("connection refused", source.getLastError());
        verify(documentService, never()).upload(any(), any(), any());
    }

    @Test
    void shouldWriteNothingWhenThePageIsUnchanged() {
        WebSource source = boundSource();
        source.setLastContentHash(HashUtil.sha256Hex(BODY));
        when(webPageFetcher.fetch(fetchOf(URL))).thenReturn(page());

        service.sync(source, TENANT_ID);

        assertEquals(WebSourceFetchStatus.UNCHANGED, source.getLastFetchStatus());
        assertNull(source.getLastError());
        verify(documentService, never()).upload(any(), any(), any());
        verify(webSourceMapper).updateById(source);
    }

    @Test
    void shouldSkipWhileTheBoundDocumentSitsInTheTrash() {
        // Feeding a version into a trashed document would silently resurrect removed content.
        WebSource source = boundSource();
        when(webPageFetcher.fetch(fetchOf(URL))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(trashedDocument());

        service.sync(source, TENANT_ID);

        assertEquals(WebSourceFetchStatus.SKIPPED, source.getLastFetchStatus());
        assertNotNull(source.getLastError());
        verify(documentService, never()).upload(any(), any(), any());
    }

    @Test
    void shouldRebindAfterAPurgeRemovedTheDocument() {
        // The purge broke the binding; the next sync recreates the document under the same stable
        // file name and the registration follows it.
        WebSource source = boundSource();
        when(webPageFetcher.fetch(fetchOf(URL))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(KB_ID, source.getFileName(), BODY)).thenReturn(outcome("doc_new"));

        service.sync(source, TENANT_ID);

        assertEquals(WebSourceFetchStatus.SUCCESS, source.getLastFetchStatus());
        assertEquals("doc_new", source.getDocId());
        verify(documentService).upload(KB_ID, "example.com-docs-guide-abcd1234.html", BODY);
    }

    @Test
    void shouldRecordAFailureTruncatedAndNeverRethrow() {
        WebSource source = boundSource();
        when(webPageFetcher.fetch(fetchOf(URL))).thenThrow(new IllegalStateException("x".repeat(600)));

        assertDoesNotThrow(() -> service.sync(source, TENANT_ID));

        assertEquals(WebSourceFetchStatus.FAILED, source.getLastFetchStatus());
        // The cause must fit the 512 char column instead of failing the status write itself.
        assertEquals(512, source.getLastError().length());
        verify(webSourceMapper).updateById(source);
    }

    @Test
    void shouldSkipTheScheduledPassWhenDisabled() {
        properties.getWebImport().setSyncEnabled(false);

        service.scheduledSync();

        verify(webSourceMapper, never()).selectList(any());
    }

    @Test
    void shouldSyncOneBoundedBatchAndContinuePastAFailingSource() {
        properties.getWebImport().setSyncBatchSize(2);
        WebSource failing = boundSource();
        WebSource healthy = boundSource();
        healthy.setSourceId("ws_2");
        healthy.setUrl(URL + "/second");
        when(webSourceMapper.selectList(any())).thenReturn(List.of(failing, healthy));
        when(webPageFetcher.fetch(fetchOf(URL))).thenThrow(new IllegalStateException("down"));
        when(webPageFetcher.fetch(fetchOf(URL + "/second"))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(any(), any(), any())).thenReturn(outcome("doc_2"));

        assertEquals(2, service.syncEnabledSources());

        assertEquals(WebSourceFetchStatus.FAILED, failing.getLastFetchStatus());
        assertEquals(WebSourceFetchStatus.SUCCESS, healthy.getLastFetchStatus());
    }

    @Test
    void shouldStopFetchingAHostAfterOneAuthenticationRejection() {
        // The M18 fail-fast: sites like Confluence CAPTCHA-lock an account after a few failed
        // logins, so the first 401 of a host must be the last request the pass sends there. A
        // generic failure (the previous test) must NOT trigger this - only the auth rejection.
        properties.getWebImport().setSyncBatchSize(3);
        WebSource first = boundSource();
        WebSource sameHost = boundSource();
        sameHost.setSourceId("ws_2");
        sameHost.setUrl(URL + "/second");
        WebSource otherHost = boundSource();
        otherHost.setSourceId("ws_3");
        otherHost.setUrl("https://other.example.org/page");
        when(webSourceMapper.selectList(any())).thenReturn(List.of(first, sameHost, otherHost));
        when(webPageFetcher.fetch(fetchOf(URL))).thenThrow(new WebAuthException("401"));
        when(webPageFetcher.fetch(fetchOf("https://other.example.org/page"))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(any(), any(), any())).thenReturn(outcome("doc_2"));

        assertEquals(3, service.syncEnabledSources());

        assertEquals(WebSourceFetchStatus.FAILED, first.getLastFetchStatus());
        assertEquals(WebSourceFetchStatus.FAILED, sameHost.getLastFetchStatus());
        assertNotNull(sameHost.getLastError());
        // The same-host sibling was skipped without a request; the foreign host proceeded.
        verify(webPageFetcher, never()).fetch(fetchOf(URL + "/second"));
        assertEquals(WebSourceFetchStatus.SUCCESS, otherHost.getLastFetchStatus());
    }

    @Test
    void shouldResolveTheCredentialByHostAndHandItToTheFetcher() {
        // The service owns the lookup, the fetcher only receives the resolved header form; this is
        // the seam that keeps infrastructure free of database access.
        WebSource source = boundSource();
        source.setLastContentHash(HashUtil.sha256Hex(BODY));
        FetchCredential credential = new FetchCredential("example.com", "Authorization", "Basic dTpw");
        when(webCredentialService.resolveFor(TENANT_ID, "example.com")).thenReturn(credential);
        when(webPageFetcher.fetch(any(WebPageFetcher.FetchRequest.class))).thenReturn(page());

        service.sync(source, TENANT_ID);

        ArgumentCaptor<WebPageFetcher.FetchRequest> captor =
                ArgumentCaptor.forClass(WebPageFetcher.FetchRequest.class);
        verify(webPageFetcher).fetch(captor.capture());
        assertEquals(credential, captor.getValue().credential());
        verify(webCredentialService, times(1)).resolveFor(TENANT_ID, "example.com");
    }

    @Test
    void shouldAskForCredentialsOfTheTenantThatOwnsTheBase() {
        // The core of the V22 fix, on the path that actually runs unattended. The scheduled pass
        // carries no principal, so nothing downstream can work out whose credentials this fetch may
        // spend - the tenant has to be resolved here, from the base the registration feeds, and
        // handed over explicitly. Two bases of two tenants registering the same wiki is the whole
        // point: each must reach its own row, and neither may reach the other's.
        WebSource mine = boundSource();
        WebSource theirs = boundSource();
        theirs.setSourceId("ws_2");
        theirs.setKbId("kb_2");
        theirs.setUrl(URL + "/theirs");
        when(knowledgeBaseService.find("kb_2")).thenReturn(base("kb_2", OTHER_TENANT_ID));
        when(webSourceMapper.selectList(any())).thenReturn(List.of(mine, theirs));
        when(webPageFetcher.fetch(any(WebPageFetcher.FetchRequest.class))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(any(), any(), any())).thenReturn(outcome("doc_2"));

        service.syncEnabledSources();

        // Same host, two tenants, two different lookups - and never a lookup without a tenant.
        verify(webCredentialService).resolveFor(TENANT_ID, "example.com");
        verify(webCredentialService).resolveFor(OTHER_TENANT_ID, "example.com");
        verify(webCredentialService, never()).resolveFor(eq(null), anyString());
    }

    @Test
    void shouldSyncWithoutACredentialWhenTheBaseOfARegistrationIsGone() {
        // Deleting a base leaves its registrations behind, so the pass does meet orphans. An orphan
        // has no tenant, and "no tenant" must mean "no credential" - the one thing it may never do
        // is fall back to whichever tenant holds that host.
        WebSource orphan = boundSource();
        orphan.setKbId("kb_deleted");
        when(knowledgeBaseService.find("kb_deleted")).thenReturn(null);
        when(webSourceMapper.selectList(any())).thenReturn(List.of(orphan));
        when(webPageFetcher.fetch(any(WebPageFetcher.FetchRequest.class))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(any(), any(), any()))
                .thenThrow(new IllegalStateException("knowledge base not found"));

        assertDoesNotThrow(() -> service.syncEnabledSources());

        verify(webCredentialService).resolveFor(null, "example.com");
        assertEquals(WebSourceFetchStatus.FAILED, orphan.getLastFetchStatus());
    }

    @Test
    void shouldFenceAnAuthenticationRejectionPerTenantRatherThanPerHost() {
        // One tenant's stale password must not cancel another tenant's fetches of the same wiki.
        // The lock the fence protects against is per account, and since V22 two tenants on one host
        // are two accounts - keying the fence by host alone would punish a tenant for a credential
        // it does not own and cannot see.
        properties.getWebImport().setSyncBatchSize(3);
        WebSource rejected = boundSource();
        WebSource sameTenantSameHost = boundSource();
        sameTenantSameHost.setSourceId("ws_2");
        sameTenantSameHost.setUrl(URL + "/sibling");
        WebSource otherTenantSameHost = boundSource();
        otherTenantSameHost.setSourceId("ws_3");
        otherTenantSameHost.setKbId("kb_2");
        otherTenantSameHost.setUrl(URL + "/theirs");
        when(knowledgeBaseService.find("kb_2")).thenReturn(base("kb_2", OTHER_TENANT_ID));
        when(webSourceMapper.selectList(any()))
                .thenReturn(List.of(rejected, sameTenantSameHost, otherTenantSameHost));
        when(webPageFetcher.fetch(fetchOf(URL))).thenThrow(new WebAuthException("401"));
        when(webPageFetcher.fetch(fetchOf(URL + "/theirs"))).thenReturn(page());
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentService.upload(any(), any(), any())).thenReturn(outcome("doc_2"));

        assertEquals(3, service.syncEnabledSources());

        // The sibling of the same tenant is skipped without a request, as before.
        assertEquals(WebSourceFetchStatus.FAILED, sameTenantSameHost.getLastFetchStatus());
        verify(webPageFetcher, never()).fetch(fetchOf(URL + "/sibling"));
        // The other tenant's registration of the same host proceeds untouched.
        assertEquals(WebSourceFetchStatus.SUCCESS, otherTenantSameHost.getLastFetchStatus());
    }

    @Test
    void shouldSwallowAFailureOfTheScheduledPass() {
        when(webSourceMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.scheduledSync());
    }

    @Test
    void shouldRemoveTheRegistrationPhysically() {
        // A soft delete would hold uk_kb_url hostage and make the URL unregisterable forever.
        WebSource source = boundSource();
        source.setId(7L);
        when(webSourceMapper.selectOne(any())).thenReturn(source);

        service.remove(SOURCE_ID);

        verify(webSourceMapper).hardDeleteById(7L);
        verify(webSourceMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void shouldDeriveAStableBoundedFileName() {
        String urlHash = HashUtil.sha256Hex(URL);
        String name = WebSourceService.deriveFileName(URI.create(URL), urlHash, "html");

        assertEquals("example.com-docs-guide-" + urlHash.substring(0, 8) + ".html", name);
        // A root URL keeps just the host.
        assertTrue(WebSourceService.deriveFileName(URI.create("https://example.com/"), urlHash, "html")
                .startsWith("example.com-"));
        // A pathological path is truncated but the disambiguating hash suffix always survives.
        String longName = WebSourceService.deriveFileName(
                URI.create("https://example.com/" + "a/".repeat(300)), urlHash, "html");
        assertEquals(200, longName.length());
        assertTrue(longName.endsWith("-" + urlHash.substring(0, 8) + ".html"));
    }

    @Test
    void shouldPassTheRenderFlagOfTheSourceToTheFetcher() {
        // The per-source render_js switch, the M17 contract: a source registered to render must reach
        // the fetcher with true, and the dispatcher decides from there whether a browser runs.
        WebSource source = boundSource();
        source.setRenderJs(1);
        source.setLastContentHash(HashUtil.sha256Hex(BODY));
        when(webPageFetcher.fetch(argThat(r -> r != null && r.url().equals(URL) && r.renderJs())))
                .thenReturn(page());

        service.sync(source, TENANT_ID);

        assertEquals(WebSourceFetchStatus.UNCHANGED, source.getLastFetchStatus());
    }

    /** Matcher for "a fetch request of this URL", credential and render flag ignored. */
    private static WebPageFetcher.FetchRequest fetchOf(String url) {
        return argThat(r -> r != null && r.url().equals(url));
    }

    private KnowledgeBase base(String kbId, String tenantId) {
        KnowledgeBase base = new KnowledgeBase();
        base.setKbId(kbId);
        base.setTenantId(tenantId);
        return base;
    }

    private WebSource boundSource() {
        WebSource source = new WebSource();
        source.setSourceId(SOURCE_ID);
        source.setKbId(KB_ID);
        source.setUrl(URL);
        source.setUrlHash(HashUtil.sha256Hex(URL));
        source.setDocId("doc_1");
        source.setFileName("example.com-docs-guide-abcd1234.html");
        source.setSyncEnabled(1);
        return source;
    }

    private Document trashedDocument() {
        Document document = new Document();
        document.setDocId("doc_1");
        document.setKbId(KB_ID);
        document.setTrashed(1);
        return document;
    }

    private WebPageFetcher.FetchedPage page() {
        return new WebPageFetcher.FetchedPage(BODY, "html", URL);
    }

    private UploadOutcome outcome(String docId) {
        Document document = new Document();
        document.setDocId(docId);
        document.setKbId(KB_ID);
        return new UploadOutcome(document, "dv_1", "v1", false, null);
    }
}
