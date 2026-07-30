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
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.WebSource;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.WebSourceMapper;
import io.kbrag.domain.port.WebPageFetcher;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.UrlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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

    /** Column limit of {@code last_error}; longer causes are truncated, not lost to an SQL error. */
    private static final int MAX_ERROR_LENGTH = 512;

    /** Upper bound of the derived file name, kept well under the 500 char column. */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    /** URL hash prefix appended to the file name so two URLs with alike paths never merge. */
    private static final int FILE_NAME_HASH_LENGTH = 8;

    private final WebSourceMapper webSourceMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final UrlGuard urlGuard;
    private final WebPageFetcher webPageFetcher;
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
     * @return registration row including the outcome of the first fetch
     */
    public WebSource register(String kbId, String url, boolean syncEnabled) {
        knowledgeBaseService.require(kbId);
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
        webSourceMapper.insert(source);
        log.info("web source registered, sourceId={}, kbId={}, url={}", source.getSourceId(), kbId, normalized);
        sync(source);
        return source;
    }

    /**
     * Lists the registrations of a knowledge base, most recently registered first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return page of registrations
     */
    public IPage<WebSource> list(String kbId, long page, long size) {
        return webSourceMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getKbId, kbId)
                .orderByDesc(WebSource::getId));
    }

    /**
     * Runs one sync of one source on demand.
     *
     * @param sourceId registration business id
     * @return registration row carrying the outcome
     */
    public WebSource syncNow(String sourceId) {
        WebSource source = require(sourceId);
        sync(source);
        return source;
    }

    /**
     * Flips the scheduled-sync switch of one source.
     *
     * @param sourceId registration business id
     * @param enabled  {@code true} includes the source in the scheduled pass
     * @return updated registration row
     */
    public WebSource updateSyncEnabled(String sourceId, boolean enabled) {
        WebSource source = require(sourceId);
        source.setSyncEnabled(enabled ? SYNC_ON : SYNC_OFF);
        webSourceMapper.updateById(source);
        log.info("web source sync switch updated, sourceId={}, enabled={}", sourceId, enabled);
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
        WebSource source = require(sourceId);
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
        for (WebSource source : batch) {
            sync(source);
        }
        return batch.size();
    }

    /**
     * Runs one sync of one source and records its outcome on the row. Never throws.
     *
     * <p>The five steps of the contract: re-validate the URL (DNS may have moved since
     * registration), fetch, short-circuit on an unchanged body, skip while the bound document sits
     * in the recycle bin, otherwise feed the body to the upload chain and rebind.
     *
     * @param source registration row, mutated in place with the outcome
     */
    public void sync(WebSource source) {
        source.setLastFetchAt(LocalDateTime.now());
        try {
            URI uri = urlGuard.validate(source.getUrl());
            WebPageFetcher.FetchedPage page = webPageFetcher.fetch(uri.toString());
            String contentHash = HashUtil.sha256Hex(page.body());
            if (contentHash.equals(source.getLastContentHash())) {
                record(source, WebSourceFetchStatus.UNCHANGED, null);
                return;
            }
            Document bound = findBoundDocument(source);
            if (bound != null && bound.inTrash()) {
                // Writing a version into a trashed document would silently resurrect content the
                // operator chose to remove; the page waits until they restore or purge it.
                record(source, WebSourceFetchStatus.SKIPPED, "绑定的文档在回收站中，本次同步已跳过");
                return;
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
            log.warn("web source sync failed, sourceId={}, url={}, error={}",
                    source.getSourceId(), source.getUrl(), e.getMessage());
            record(source, WebSourceFetchStatus.FAILED, e.getMessage());
        }
    }

    private void record(WebSource source, WebSourceFetchStatus status, String error) {
        source.setLastFetchStatus(status);
        source.setLastError(truncate(error));
        webSourceMapper.updateById(source);
        // The one funnel every outcome passes, which is what makes it the M13 sync counter's spot.
        kbMetrics.recordWebSourceSync(status);
    }

    private WebSource require(String sourceId) {
        WebSource source = webSourceMapper.selectOne(new LambdaQueryWrapper<WebSource>()
                .eq(WebSource::getSourceId, sourceId));
        if (source == null) {
            throw BizException.notFound("URL 登记不存在：" + sourceId);
        }
        return source;
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
