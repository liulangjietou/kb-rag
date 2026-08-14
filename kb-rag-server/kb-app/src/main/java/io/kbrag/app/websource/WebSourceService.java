package io.kbrag.app.websource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.context.ModelUsageContextHolder;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.entity.WebSource;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.WebSourceMapper;
import io.kbrag.domain.model.FetchCredential;
import io.kbrag.domain.model.ModelUsageContext;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.UrlGuard;
import io.kbrag.domain.service.WebAuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URL import and incremental sync, the M12 contract section 3.4.
 *
 * <p><b>Everything a fetch produces goes through {@link DocumentService#upload}.</b> The derived
 * file name is stable per URL, so a re-fetch lands on the "same name adds a version" path, and the
 * content-hash short circuit inside the upload chain means an unchanged page never creates a
 * version. Governance, retention and the index pipeline all apply to web content for free because
 * there is no second intake path to keep honest.
 *
 * <p><b>One sync never throws.</b> Its outcome - SUCCESS, UNCHANGED, SKIPPED or FAILED - is written
 * onto the registration row where both the operator and the next pass can read it; a page that is
 * down today is simply retried tomorrow.
 *
 * <p><b>Every fetch carries the tenant of the base it feeds</b> (V22). A registration reaches its
 * tenant through {@code kb_id}, and that tenant decides which site credential the fetch may spend.
 * It is passed explicitly rather than read from the context because the pass that needs it most -
 * the nightly one - runs on a thread that has no context: there the row level fence is off, and an
 * unqualified lookup by host would hand one tenant's password to another tenant's request.
 *
 * <p><b>Every console entry resolves through {@link WebSourceGuard} first</b> (V22 follow-up).
 * {@code t_kb_web_source} carries no {@code tenant_id} - it is a subordinate of the base - so an
 * entry going straight to it by {@code source_id} or {@code kb_id} meets no tenant clause at all.
 * The scheduled pass below is the deliberate exception: it has no principal, resolves the tenant of
 * each row itself and must see every tenant's registrations to do so.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSourceService {

    /** {@code sync_enabled} value that includes a source in the scheduled pass. */
    private static final int SYNC_ON = 1;

    /** {@code sync_enabled} value that excludes a source from the scheduled pass. */
    private static final int SYNC_OFF = 0;

    /** {@code render_js} value that fetches a source through the headless browser, the M17 contract. */
    private static final int RENDER_ON = 1;

    /** {@code render_js} value that keeps a source on the static fetch. */
    private static final int RENDER_OFF = 0;

    /** Column limit of {@code last_error}; longer causes are truncated, not lost to an SQL error. */
    private static final int MAX_ERROR_LENGTH = 512;

    /** Upper bound of the derived file name, kept well under the 500 char column. */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    /** URL hash prefix appended to the file name so two URLs with alike paths never merge. */
    private static final int FILE_NAME_HASH_LENGTH = 8;

    private final WebSourceGuard webSourceGuard;
    private final WebSourceMapper webSourceMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final UrlGuard urlGuard;
    private final WebPageFetcher webPageFetcher;
    private final WebCredentialService webCredentialService;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;
    private final KbMetrics kbMetrics;

    /**
     * Registers a URL and immediately runs its first sync.
     *
     * <p>The registration survives a failed first fetch on purpose: the operator registered the
     * page, not the luck of this minute, and the row carries the failure for them to see.
     *
     * @param kbId        knowledge base business id
     * @param url         page address, validated against the SSRF guard
     * @param syncEnabled whether the scheduled pass should include this source
     * @param renderJs    whether this source is fetched through the headless browser, the M17 switch
     * @return registration row including the outcome of the first fetch
     */
    public WebSource register(String kbId, String url, boolean syncEnabled, boolean renderJs) {
        KnowledgeBase base = webSourceGuard.requireBase(kbId);
        String normalized = urlGuard.validate(url).toString();
        String urlHash = HashUtil.sha256Hex(normalized);
        Long existing = webSourceMapper.selectCount(new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getKbId, kbId)
                .eq(WebSource::getUrlHash, urlHash));
        if (existing != null && existing > 0) {
            throw BizException.invalidParam("该 URL 已在此知识库登记，请勿重复添加");
        }
        WebSource source = new WebSource();
        source.setSourceId(bizIdGenerator.webSourceId());
        source.setKbId(kbId);
        source.setUrl(normalized);
        source.setUrlHash(urlHash);
        source.setSyncEnabled(syncEnabled ? SYNC_ON : SYNC_OFF);
        source.setRenderJs(renderJs ? RENDER_ON : RENDER_OFF);
        webSourceMapper.insert(source);
        log.info("web source registered, sourceId={}, kbId={}, url={}, renderJs={}",
                source.getSourceId(), kbId, normalized, renderJs);
        // The base was just loaded to validate the registration; its tenant is the one whose
        // credentials this URL may spend, so it rides along instead of being looked up again.
        sync(source, base.getTenantId());
        return source;
    }

    /**
     * Lists the registrations of a knowledge base, most recently registered first.
     *
     * <p>The base is resolved first and the listing statement is only reached for a base the caller
     * may see: {@code kb_id} alone selects rows of any tenant, since the registration table holds no
     * tenant of its own to filter on.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return page of registrations
     */
    public IPage<WebSource> list(String kbId, long page, long size) {
        webSourceGuard.requireBase(kbId);
        return webSourceMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getKbId, kbId)
                .orderByDesc(WebSource::getId));
    }

    /**
     * Runs one sync of one source on demand.
     *
     * <p>The tenant comes from the base the guard just resolved rather than from a second lookup:
     * that base is the one this call was authorised against, and it is the tenant whose credentials
     * this fetch may spend.
     *
     * @param sourceId registration business id
     * @return registration row carrying the outcome
     */
    public WebSource syncNow(String sourceId) {
        WebSourceGuard.ScopedWebSource scoped = webSourceGuard.requireSource(sourceId);
        sync(scoped.source(), scoped.base().getTenantId());
        return scoped.source();
    }

    /**
     * Applies the mutable switches of a registration, the M17 contract section 3.3. Both are
     * optional and only the non-null ones are written, so the console's two toggles - scheduled sync
     * and JS rendering - share one endpoint. Flipping a switch never triggers a fetch.
     *
     * @param sourceId    registration business id
     * @param syncEnabled new scheduled-sync value, {@code null} leaves it unchanged
     * @param renderJs    new JS-render value, {@code null} leaves it unchanged
     * @return updated registration row
     */
    public WebSource updateSettings(String sourceId, Boolean syncEnabled, Boolean renderJs) {
        WebSource source = webSourceGuard.requireSource(sourceId).source();
        if (syncEnabled != null) {
            source.setSyncEnabled(syncEnabled ? SYNC_ON : SYNC_OFF);
        }
        if (renderJs != null) {
            source.setRenderJs(renderJs ? RENDER_ON : RENDER_OFF);
        }
        webSourceMapper.updateById(source);
        log.info("web source settings updated, sourceId={}, syncEnabled={}, renderJs={}",
                sourceId, syncEnabled, renderJs);
        return source;
    }

    /**
     * Removes a registration; the document it fed stays untouched.
     *
     * <p>Hard delete, not the soft flag: a soft-deleted row would hold {@code uk_kb_url} hostage
     * and make the same URL unregisterable forever.
     *
     * @param sourceId registration business id
     */
    public void remove(String sourceId) {
        WebSource source = webSourceGuard.requireSource(sourceId).source();
        webSourceMapper.hardDeleteById(source.getId());
        log.info("web source removed, sourceId={}, kbId={}, docId={}",
                sourceId, source.getKbId(), source.getDocId());
    }

    /**
     * Nightly sync pass over every source whose switch is on.
     *
     * <p>Failures are logged and never rethrown: the scheduler has no caller to report to, and
     * {@link #sync} already confines each source's failure to its own row.
     */
    @Scheduled(cron = "${kb.web-import.sync-cron:0 30 2 * * *}")
    public void scheduledSync() {
        if (!properties.getWebImport().isSyncEnabled()) {
            return;
        }
        try {
            int synced = syncEnabledSources();
            if (synced > 0) {
                log.info("web source sync pass finished, sources={}", synced);
            }
        } catch (Exception e) {
            log.error("web source sync pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Syncs one bounded batch of enabled sources, oldest registration first.
     *
     * <p>A single batch per pass is intentional: page fetches are slow network calls, and an
     * operator who registered thousands of URLs should see them drain over passes rather than one
     * pass monopolising the scheduler thread for hours.
     *
     * @return sources attempted by this pass
     */
    public int syncEnabledSources() {
        int batchSize = Math.max(1, properties.getWebImport().getSyncBatchSize());
        List<WebSource> batch = webSourceMapper.selectList(new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getSyncEnabled, SYNC_ON)
                .orderByAsc(WebSource::getId)
                .last("limit " + batchSize));
        if (CollectionUtils.isEmpty(batch)) {
            return 0;
        }
        // One authentication rejection stops the whole site for this pass. Confluence-like sites
        // CAPTCHA-lock an account after a few failed logins; a pass hammering fifty URLs of one
        // wiki with a rotated password would lock it for good, so the first rejection is the last
        // request this pass sends there.
        //
        // A "site" here is (tenant, host), not the host: since V22 two tenants can each hold their
        // own account on one wiki, and a rejection of one says nothing about the other - different
        // credential, different account, different lock. Keying this by host alone would let one
        // tenant's stale password cancel every other tenant's fetches of that wiki for the night.
        Set<String> rejectedSites = new HashSet<>();
        for (WebSource source : batch) {
            String tenantId = tenantOf(source);
            String site = siteOf(tenantId, hostOf(source.getUrl()));
            if (site != null && rejectedSites.contains(site)) {
                source.setLastFetchAt(LocalDateTime.now());
                record(source, WebSourceFetchStatus.FAILED,
                        "本租户同 host 本轮已出现认证失败，跳过以防账号被锁定");
                continue;
            }
            boolean authRejected = sync(source, tenantId);
            if (authRejected && site != null) {
                rejectedSites.add(site);
            }
        }
        return batch.size();
    }

    /** Host of a stored URL, {@code null} when it no longer parses; never throws. */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Key of the "one rejection stops the site" fence, {@code null} when either half is unknown.
     *
     * <p>Returning {@code null} keeps such a row out of the fence entirely rather than filing every
     * unknown under one shared bucket, where an unparseable URL of one tenant would start skipping
     * unrelated rows of another.
     */
    private static String siteOf(String tenantId, String host) {
        return tenantId == null || host == null ? null : tenantId + "|" + host;
    }

    /**
     * Tenant that owns a registration, reached through the base it feeds; {@code null} when the base
     * is gone.
     *
     * <p>Deleting a base does not delete its registrations, so the scheduled pass does meet rows
     * whose {@code kb_id} no longer resolves. Such a row gets no credential and fails on the upload
     * a moment later - the same outcome an orphan row has always had. What must not happen is
     * falling back to a host-only lookup: that is precisely how one tenant's password reached
     * another tenant's fetch before V22.
     */
    private String tenantOf(WebSource source) {
        KnowledgeBase base = knowledgeBaseService.find(source.getKbId());
        return base == null ? null : base.getTenantId();
    }

    /**
     * Runs one sync of one source and records its outcome on the row. Never throws.
     *
     * <p>The five steps of the contract: re-validate the URL (DNS may have moved since
     * registration), fetch, short-circuit on an unchanged body, skip while the bound document sits
     * in the recycle bin, otherwise feed the body to the upload chain and rebind.
     *
     * @param source   registration row, mutated in place with the outcome
     * @param tenantId tenant of the base this registration feeds, {@code null} when that base is
     *                 gone; it decides whose credentials this fetch may spend
     * @return {@code true} when the failure was an authentication rejection - the signal the batch
     *         pass uses to stop fetching the same site, see {@link #syncEnabledSources()}
     */
    public boolean sync(WebSource source, String tenantId) {
        ModelUsageContext current = ModelUsageContextHolder.get();
        ModelUsageContext context = current != null && tenantId != null && tenantId.equals(current.tenantId())
                ? current
                : new ModelUsageContext(tenantId, ModelUsageContext.SOURCE_SCHEDULED, source.getSourceId());
        return ModelUsageContextHolder.with(context, () -> syncAttributed(source, tenantId));
    }

    /** Executes the sync after its tenant cost attribution has been bound. */
    private boolean syncAttributed(WebSource source, String tenantId) {
        source.setLastFetchAt(LocalDateTime.now());
        try {
            URI uri = urlGuard.validate(source.getUrl());
            boolean renderJs = source.getRenderJs() != null && source.getRenderJs() == RENDER_ON;
            // The credential is resolved here, per fetch, so a rotation applies to the very next
            // sync; the fetcher only ever sees the ready-to-inject header form. The tenant travels
            // with the call because this runs on the scheduled thread as often as not, and there
            // the row level fence is off - see WebCredentialService#resolveFor.
            FetchCredential credential = webCredentialService.resolveFor(tenantId, uri.getHost());
            WebPageFetcher.FetchedPage page = webPageFetcher.fetch(
                    new WebPageFetcher.FetchRequest(uri.toString(), renderJs, credential));
            String contentHash = HashUtil.sha256Hex(page.body());
            if (contentHash.equals(source.getLastContentHash())) {
                record(source, WebSourceFetchStatus.UNCHANGED, null);
                return false;
            }
            Document bound = findBoundDocument(source);
            if (bound != null && bound.inTrash()) {
                // Writing a version into a trashed document would silently resurrect content the
                // operator chose to remove; the page waits until they restore or purge it.
                record(source, WebSourceFetchStatus.SKIPPED, "绑定的文档在回收站中，本次同步已跳过");
                return false;
            }
            String fileName = source.getFileName() != null
                    ? source.getFileName()
                    : deriveFileName(uri, source.getUrlHash(), page.extension());
            UploadOutcome outcome = documentService.upload(source.getKbId(), fileName, page.body());
            // A purge breaks the binding; the upload above then created a fresh document and the
            // registration follows it, which is the weak-binding semantics of the contract.
            source.setDocId(outcome.document().getDocId());
            source.setFileName(fileName);
            source.setLastContentHash(contentHash);
            record(source, WebSourceFetchStatus.SUCCESS, null);
            log.info("web source synced, sourceId={}, docId={}, version={}, duplicated={}",
                    source.getSourceId(), source.getDocId(), outcome.version(), outcome.duplicated());
        } catch (Exception e) {
            log.info("web source sync failed, sourceId={}, url={}, error={}",
                    source.getSourceId(), source.getUrl(), e.getMessage());
            record(source, WebSourceFetchStatus.FAILED, e.getMessage());
            return e instanceof WebAuthException;
        }
        return false;
    }

    private void record(WebSource source, WebSourceFetchStatus status, String error) {
        source.setLastFetchStatus(status);
        source.setLastError(truncate(error));
        webSourceMapper.updateById(source);
        // The one funnel every outcome passes, which is what makes it the M13 sync counter's spot.
        kbMetrics.recordWebSourceSync(status);
    }

    /**
     * Bound document of a source, {@code null} when never bound or when a purge removed it.
     */
    private Document findBoundDocument(WebSource source) {
        if (source.getDocId() == null) {
            return null;
        }
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, source.getDocId()));
    }

    /**
     * Derives the stable file name a URL uploads under: host, a slug of the path and a hash prefix
     * that keeps two different URLs with alike paths from merging into one document.
     */
    static String deriveFileName(URI uri, String urlHash, String extension) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String slug = slugOf(uri.getRawPath());
        String base = slug.isEmpty() ? host : host + "-" + slug;
        String suffix = "-" + urlHash.substring(0, FILE_NAME_HASH_LENGTH) + "." + extension;
        int room = MAX_FILE_NAME_LENGTH - suffix.length();
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return base + suffix;
    }

    /** Lower-cases a URL path and collapses every non-alphanumeric run into a single dash. */
    private static String slugOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String slug = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
