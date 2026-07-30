package io.kbrag.app.extsource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.UploadOutcome;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.ExtSource;
import io.kbrag.domain.entity.ExtSourceItem;
import io.kbrag.domain.enums.ExtSourceItemStatus;
import io.kbrag.domain.enums.ExtSourceSyncStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.ExtSourceItemMapper;
import io.kbrag.domain.mapper.ExtSourceMapper;
import io.kbrag.domain.model.ExtSourceConfig;
import io.kbrag.domain.model.HealthStatus;
import io.kbrag.domain.port.ExternalConnector;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ConnectorRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * External data source registration and incremental sync, the M14 contract section 2.
 *
 * <p><b>Everything a scan produces goes through {@link DocumentService#upload}</b>, the M12 rule
 * kept intact: the derived file name is stable per object key, so a changed object lands on the
 * "same name adds a version" path, and governance, retention and the index pipeline apply to
 * connector content for free because there is no second intake path to keep honest.
 *
 * <p><b>A sync runs off the request thread.</b> One scan visits an unbounded number of objects
 * over slow outbound I/O, so registration and manual sync only hand the scan to the executor -
 * the M12 register-fetches-inline shape would let one large bucket park a console request.
 *
 * <p><b>One scan never throws.</b> The source level outcome - SUCCESS, PARTIAL or FAILED - is
 * written onto the source row and each visited object writes its own item row, so the operator
 * reads exactly what one pass did and the next pass simply tries again.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtSourceService {

    /** {@code sync_enabled} value that includes a source in the scheduled pass. */
    private static final int SYNC_ON = 1;

    /** {@code sync_enabled} value that excludes a source from the scheduled pass. */
    private static final int SYNC_OFF = 0;

    /** Column limit of {@code last_error}; longer causes are truncated, not lost to an SQL error. */
    private static final int MAX_ERROR_LENGTH = 512;

    /** Upper bound of the derived file name, kept well under the 500 char column. */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    /** Key hash prefix appended to the file name so two keys with alike names never merge. */
    private static final int FILE_NAME_HASH_LENGTH = 8;

    private final ExtSourceMapper extSourceMapper;
    private final ExtSourceItemMapper extSourceItemMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ConnectorRouter connectorRouter;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;
    private final KbMetrics kbMetrics;

    /**
     * Registers an external source. The first scan is not run here: the caller hands the fresh
     * source id to {@link #syncAsync} so the console gets its response before the bucket is walked.
     *
     * @param kbId    knowledge base business id
     * @param command connection details and the sync switch
     * @return persisted source row, secret included - the caller's view layer masks it
     */
    public ExtSource register(String kbId, ExtSourceCommand command) {
        knowledgeBaseService.require(kbId);
        // Resolving up front rejects an unknown type at registration time instead of on the first
        // scan, when the operator is no longer looking.
        connectorRouter.resolve(command.sourceType());
        requireNameFree(kbId, command.name(), null);
        ExtSource source = new ExtSource();
        source.setSourceId(bizIdGenerator.extSourceId());
        source.setKbId(kbId);
        source.setSourceType(command.sourceType().toLowerCase(Locale.ROOT));
        source.setName(command.name());
        source.setEndpoint(command.endpoint());
        source.setRegion(command.region());
        source.setBucket(command.bucket());
        source.setPrefix(command.prefix());
        source.setAccessKey(command.accessKey());
        source.setSecretKey(command.secretKey());
        source.setSyncEnabled(Boolean.FALSE.equals(command.syncEnabled()) ? SYNC_OFF : SYNC_ON);
        extSourceMapper.insert(source);
        log.info("ext source registered, sourceId={}, kbId={}, type={}, bucket={}",
                source.getSourceId(), kbId, source.getSourceType(), source.getBucket());
        return source;
    }

    /**
     * Lists the sources of a knowledge base, most recently registered first.
     *
     * @param kbId knowledge base business id
     * @param page one based page number
     * @param size page size
     * @return page of sources
     */
    public IPage<ExtSource> list(String kbId, long page, long size) {
        return extSourceMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ExtSource>()
                .eq(ExtSource::getKbId, kbId)
                .orderByDesc(ExtSource::getId));
    }

    /**
     * Lists the per object sync outcomes of one source, most recently discovered first.
     *
     * @param sourceId source business id
     * @param page     one based page number
     * @param size     page size
     * @return page of item rows
     */
    public IPage<ExtSourceItem> listItems(String sourceId, long page, long size) {
        require(sourceId);
        return extSourceItemMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ExtSourceItem>()
                .eq(ExtSourceItem::getSourceId, sourceId)
                .orderByDesc(ExtSourceItem::getId));
    }

    /**
     * Updates the connection details of one source.
     *
     * <p>A blank secret keeps the stored one: the read API never returns the secret, so an edit
     * form that echoes what it was given would otherwise overwrite the credential with the mask.
     *
     * @param sourceId source business id
     * @param command  new values; {@code null} sync switch keeps the current one
     * @return updated source row
     */
    public ExtSource update(String sourceId, ExtSourceCommand command) {
        ExtSource source = require(sourceId);
        if (!source.getName().equals(command.name())) {
            requireNameFree(source.getKbId(), command.name(), source.getId());
        }
        source.setName(command.name());
        source.setEndpoint(command.endpoint());
        source.setRegion(command.region());
        source.setBucket(command.bucket());
        source.setPrefix(command.prefix());
        source.setAccessKey(command.accessKey());
        if (command.secretKey() != null && !command.secretKey().isBlank()) {
            source.setSecretKey(command.secretKey());
        }
        if (command.syncEnabled() != null) {
            source.setSyncEnabled(command.syncEnabled() ? SYNC_ON : SYNC_OFF);
        }
        extSourceMapper.updateById(source);
        log.info("ext source updated, sourceId={}, bucket={}, syncEnabled={}",
                sourceId, source.getBucket(), source.getSyncEnabled());
        return source;
    }

    /**
     * Probes connectivity and credentials of one source without touching any object.
     *
     * @param sourceId source business id
     * @return probe outcome
     */
    public HealthStatus testConnection(String sourceId) {
        ExtSource source = require(sourceId);
        ExternalConnector connector = connectorRouter.resolve(source.getSourceType());
        return connector.testConnection(configOf(source));
    }

    /**
     * Removes a source and its item rows; the documents they fed stay untouched.
     *
     * <p>Hard delete, not the soft flag: a soft-deleted row would hold {@code uk_kb_name} hostage
     * and make the same name unregisterable forever.
     *
     * @param sourceId source business id
     */
    public void remove(String sourceId) {
        ExtSource source = require(sourceId);
        extSourceItemMapper.hardDeleteBySourceId(sourceId);
        extSourceMapper.hardDeleteById(source.getId());
        log.info("ext source removed, sourceId={}, kbId={}", sourceId, source.getKbId());
    }

    /**
     * Asserts a source exists, used by the sync endpoint before the scan is accepted: an unknown
     * id must fail the request, not disappear into an executor that has no caller to tell.
     *
     * @param sourceId source business id
     */
    public void ensureExists(String sourceId) {
        require(sourceId);
    }

    /**
     * Runs one scan of one source on the dedicated executor.
     *
     * <p>Never throws: an asynchronous scan has no caller to report to, and {@link #syncSource}
     * already confines every failure to the rows the operator reads.
     *
     * @param sourceId source business id
     */
    @Async(AsyncConfig.EXT_SOURCE_EXECUTOR)
    public void syncAsync(String sourceId) {
        try {
            syncSource(require(sourceId));
        } catch (Exception e) {
            log.error("ext source async sync failed, errorCode={}, sourceId={}",
                    ErrorCode.INTERNAL_ERROR, sourceId, e);
        }
    }

    /**
     * Nightly sync pass over every source whose switch is on.
     */
    @Scheduled(cron = "${kb.ext-source.sync-cron:0 0 3 * * *}")
    public void scheduledSync() {
        if (!properties.getExtSource().isSyncEnabled()) {
            return;
        }
        try {
            int synced = syncEnabledSources();
            if (synced > 0) {
                log.info("ext source sync pass finished, sources={}", synced);
            }
        } catch (Exception e) {
            log.error("ext source sync pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Syncs one bounded batch of enabled sources, oldest registration first.
     *
     * <p>The bound is smaller than the web import one because one source here is a whole bucket
     * scan, not a single page fetch.
     *
     * @return sources attempted by this pass
     */
    public int syncEnabledSources() {
        int batchSize = Math.max(1, properties.getExtSource().getSyncBatchSize());
        List<ExtSource> batch = extSourceMapper.selectList(new LambdaQueryWrapper<ExtSource>()
                .eq(ExtSource::getSyncEnabled, SYNC_ON)
                .orderByAsc(ExtSource::getId)
                .last("limit " + batchSize));
        if (CollectionUtils.isEmpty(batch)) {
            return 0;
        }
        for (ExtSource source : batch) {
            syncSource(source);
        }
        return batch.size();
    }

    /**
     * Runs one scan of one source and records its outcome on the row. Never throws.
     *
     * <p>The contract steps: probe the connection first (a dead store fails the source, not five
     * hundred objects), list, visit each object through {@link #syncObject}, mark the item rows
     * whose objects vanished from the bucket, then fold the visit outcomes into the source status.
     *
     * @param source source row, mutated in place with the outcome
     */
    public void syncSource(ExtSource source) {
        source.setLastSyncAt(LocalDateTime.now());
        try {
            ExternalConnector connector = connectorRouter.resolve(source.getSourceType());
            ExtSourceConfig config = configOf(source);
            HealthStatus health = connector.testConnection(config);
            if (!health.isUp()) {
                recordSource(source, ExtSourceSyncStatus.FAILED, health.getDetail());
                return;
            }
            List<ExternalConnector.RemoteObject> listed = connector.listObjects(config);
            int cap = config.maxObjects();
            boolean truncated = listed.size() > cap;
            List<ExternalConnector.RemoteObject> objects = truncated ? listed.subList(0, cap) : listed;
            List<String> allowedExtensions = properties.getUpload().getAllowedExtensions();
            Set<String> seenKeyHashes = new HashSet<>();
            int failures = 0;
            for (ExternalConnector.RemoteObject object : objects) {
                // Seen before the whitelist filter: an object that exists but is not ingestible
                // must not be reported as vanished below.
                seenKeyHashes.add(HashUtil.sha256Hex(object.key()));
                String extension = extensionOf(object.key());
                if (extension == null || !allowedExtensions.contains(extension)) {
                    continue;
                }
                if (!syncObject(source, connector, config, object, extension)) {
                    failures++;
                }
            }
            if (!truncated) {
                // A truncated listing proves nothing about what it did not show, so vanished
                // objects are only marked when the listing was complete.
                markVanishedItems(source, seenKeyHashes);
            }
            recordSource(source,
                    failures > 0 || truncated ? ExtSourceSyncStatus.PARTIAL : ExtSourceSyncStatus.SUCCESS,
                    partialError(failures, truncated, cap));
            log.info("ext source synced, sourceId={}, objects={}, failures={}, truncated={}",
                    source.getSourceId(), objects.size(), failures, truncated);
        } catch (Exception e) {
            log.warn("ext source sync failed, sourceId={}, bucket={}, error={}",
                    source.getSourceId(), source.getBucket(), e.getMessage());
            recordSource(source, ExtSourceSyncStatus.FAILED, e.getMessage());
        }
    }

    /**
     * Visits one listed object; its outcome lands on the item row. Returns {@code false} only for
     * FAILED - unchanged and skipped are successes of a kind, the M12 reading.
     */
    private boolean syncObject(ExtSource source, ExternalConnector connector, ExtSourceConfig config,
                               ExternalConnector.RemoteObject object, String extension) {
        String keyHash = HashUtil.sha256Hex(object.key());
        ExtSourceItem item = findItem(source.getSourceId(), keyHash);
        if (item == null) {
            item = new ExtSourceItem();
            item.setSourceId(source.getSourceId());
            item.setObjectKey(object.key());
            item.setObjectKeyHash(keyHash);
        }
        item.setLastSyncAt(LocalDateTime.now());
        try {
            if (object.etag() != null && object.etag().equals(item.getEtag())) {
                recordItem(item, ExtSourceItemStatus.UNCHANGED, null);
                return true;
            }
            Document bound = findBoundDocument(item);
            if (bound != null && bound.inTrash()) {
                // Writing a version into a trashed document would silently resurrect content the
                // operator chose to remove; the object waits until they restore or purge it.
                recordItem(item, ExtSourceItemStatus.SKIPPED, "绑定的文档在回收站中，本次同步已跳过");
                return true;
            }
            byte[] body = connector.fetchObject(config, object.key());
            String fileName = deriveFileName(object.key(), keyHash, extension);
            UploadOutcome outcome = documentService.upload(source.getKbId(), fileName, body);
            // A purge breaks the binding; the upload above then created a fresh document and the
            // item follows it, the same weak binding semantics as the web source contract.
            item.setDocId(outcome.document().getDocId());
            item.setEtag(object.etag());
            recordItem(item, ExtSourceItemStatus.SUCCESS, null);
            return true;
        } catch (Exception e) {
            log.warn("ext source object sync failed, sourceId={}, object={}, error={}",
                    source.getSourceId(), object.key(), e.getMessage());
            recordItem(item, ExtSourceItemStatus.FAILED, e.getMessage());
            return false;
        }
    }

    /**
     * Marks the item rows whose objects a complete listing no longer contains.
     *
     * <p>The binding is weak on purpose: the document stays, only the item row says the bucket
     * side is gone, so the operator learns it from the console instead of from a silent no-op.
     */
    private void markVanishedItems(ExtSource source, Set<String> seenKeyHashes) {
        List<ExtSourceItem> items = extSourceItemMapper.selectList(new LambdaQueryWrapper<ExtSourceItem>()
                .eq(ExtSourceItem::getSourceId, source.getSourceId()));
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        for (ExtSourceItem item : items) {
            if (seenKeyHashes.contains(item.getObjectKeyHash())
                    || item.getLastStatus() == ExtSourceItemStatus.SKIPPED) {
                continue;
            }
            item.setLastSyncAt(LocalDateTime.now());
            recordItem(item, ExtSourceItemStatus.SKIPPED, "对象已不存在，绑定的文档保持不变");
        }
    }

    private void recordSource(ExtSource source, ExtSourceSyncStatus status, String error) {
        source.setLastSyncStatus(status);
        source.setLastError(truncate(error));
        extSourceMapper.updateById(source);
        // The one funnel every outcome passes, the same spot the M13 counter sits for web sources.
        kbMetrics.recordExtSourceSync(status);
    }

    private void recordItem(ExtSourceItem item, ExtSourceItemStatus status, String error) {
        item.setLastStatus(status);
        item.setLastError(truncate(error));
        if (item.getId() == null) {
            extSourceItemMapper.insert(item);
        } else {
            extSourceItemMapper.updateById(item);
        }
    }

    private ExtSource require(String sourceId) {
        ExtSource source = extSourceMapper.selectOne(new LambdaQueryWrapper<ExtSource>()
                .eq(ExtSource::getSourceId, sourceId));
        if (source == null) {
            throw BizException.notFound("外部数据源不存在：" + sourceId);
        }
        return source;
    }

    private void requireNameFree(String kbId, String name, Long selfId) {
        Long count = extSourceMapper.selectCount(new LambdaQueryWrapper<ExtSource>()
                .eq(ExtSource::getKbId, kbId)
                .eq(ExtSource::getName, name)
                .ne(selfId != null, ExtSource::getId, selfId));
        if (count != null && count > 0) {
            throw BizException.invalidParam("该名称已在此知识库使用，请换一个名称");
        }
    }

    private ExtSourceItem findItem(String sourceId, String keyHash) {
        return extSourceItemMapper.selectOne(new LambdaQueryWrapper<ExtSourceItem>()
                .eq(ExtSourceItem::getSourceId, sourceId)
                .eq(ExtSourceItem::getObjectKeyHash, keyHash));
    }

    /**
     * Bound document of an item, {@code null} when never bound or when a purge removed it.
     */
    private Document findBoundDocument(ExtSourceItem item) {
        if (item.getDocId() == null) {
            return null;
        }
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, item.getDocId()));
    }

    private ExtSourceConfig configOf(ExtSource source) {
        KbProperties.ExtSource policy = properties.getExtSource();
        return new ExtSourceConfig(
                source.getEndpoint(),
                source.getRegion(),
                source.getBucket(),
                source.getPrefix(),
                source.getAccessKey(),
                source.getSecretKey(),
                policy.getMaxObjectsPerSource(),
                policy.getFetchTimeoutMs());
    }

    private String partialError(int failures, boolean truncated, int cap) {
        if (failures == 0 && !truncated) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        if (truncated) {
            message.append("对象数超过上限 ").append(cap).append("，本次仅同步前 ").append(cap).append(" 个");
        }
        if (failures > 0) {
            if (message.length() > 0) {
                message.append("；");
            }
            message.append(failures).append(" 个对象同步失败，详见对象明细");
        }
        return message.toString();
    }

    /** Lower case extension of an object key, {@code null} when the key has none. */
    static String extensionOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        String name = slash < 0 ? objectKey : objectKey.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Derives the stable file name an object uploads under: a slug of the object's base name and a
     * hash prefix that keeps two different keys with alike names from merging into one document.
     */
    static String deriveFileName(String objectKey, String keyHash, String extension) {
        int slash = objectKey.lastIndexOf('/');
        String name = slash < 0 ? objectKey : objectKey.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String base = slugOf(dot <= 0 ? name : name.substring(0, dot));
        String suffix = "-" + keyHash.substring(0, FILE_NAME_HASH_LENGTH) + "." + extension;
        int room = MAX_FILE_NAME_LENGTH - suffix.length();
        if (base.length() > room) {
            base = base.substring(0, room);
        }
        return base + suffix;
    }

    /** Lower-cases a name and collapses every non-alphanumeric run into a single dash. */
    private static String slugOf(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
