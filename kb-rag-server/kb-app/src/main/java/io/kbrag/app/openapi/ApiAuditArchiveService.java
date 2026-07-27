package io.kbrag.app.openapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiAuditLog;
import io.kbrag.domain.mapper.ApiAuditLogMapper;
import io.kbrag.domain.port.ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Moves audit rows past their retention window into object storage, requirement section 4.8 "kept for 180
 * days by default, then archived as a compressed file and deleted from the database in bounded batches".
 *
 * <p><b>Write first, delete second, one batch at a time.</b> The archive object is uploaded before the rows it
 * contains are removed, so a crash between the two steps leaves a duplicate archive rather than a hole - the
 * only acceptable direction for an audit trail. The batch bound is what keeps the delete off a long
 * transaction while the request path keeps inserting into the same table.
 *
 * <p>The delete is physical rather than logical. An audit table grows without bound by design; a logical
 * delete would keep every row forever and turn a retention policy into a column update.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiAuditArchiveService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String OBJECT_SUFFIX = ".json.gz";
    private static final String CONTENT_TYPE_GZIP = "application/gzip";
    private static final String LINE_BREAK = "\n";

    private final ApiAuditLogMapper apiAuditLogMapper;
    private final ObjectStorage objectStorage;
    private final KbProperties properties;

    /**
     * Daily archive pass.
     *
     * <p>Failures are logged and never rethrown: the scheduler has no caller to report to, and one skipped
     * pass costs table size until the next one, which is not a correctness problem.
     */
    @Scheduled(cron = "${kb.open-api.audit-archive-cron:0 30 3 * * *}")
    public void scheduledArchive() {
        if (!properties.getOpenApi().isAuditArchiveEnabled()) {
            return;
        }
        try {
            int archived = archiveExpired();
            if (archived > 0) {
                log.info("api audit archive pass finished, archivedRows={}", archived);
            }
        } catch (Exception e) {
            log.error("api audit archive pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Archives and removes every row older than the retention window.
     *
     * @return rows archived by this pass
     */
    public int archiveExpired() {
        int batchSize = Math.max(1, properties.getOpenApi().getAuditArchiveBatchSize());
        LocalDateTime horizon = LocalDate.now()
                .minusDays(properties.getOpenApi().getAuditRetentionDays())
                .atStartOfDay();
        int archivedTotal = 0;
        while (true) {
            List<ApiAuditLog> batch = apiAuditLogMapper.selectList(new LambdaQueryWrapper<ApiAuditLog>()
                    .lt(ApiAuditLog::getCreatedAt, horizon)
                    .orderByAsc(ApiAuditLog::getId)
                    .last("limit " + batchSize));
            if (CollectionUtils.isEmpty(batch)) {
                return archivedTotal;
            }
            long maxId = batch.get(batch.size() - 1).getId();
            byte[] payload = gzipOf(batch);
            objectStorage.put(objectKeyOf(horizon), new ByteArrayInputStream(payload),
                    payload.length, CONTENT_TYPE_GZIP);
            int deleted = apiAuditLogMapper.purgeArchived(maxId, horizon, batchSize);
            archivedTotal += deleted;
            log.info("api audit rows archived, horizon={}, batch={}, deleted={}", horizon, batch.size(), deleted);
            if (deleted == 0) {
                // Nothing was removed although rows matched: retrying the same batch forever would spin, so
                // the pass stops and the next scheduled one tries again.
                log.error("api audit archive removed no row despite a matching batch, errorCode={}, maxId={}",
                        ErrorCode.INTERNAL_ERROR, maxId);
                return archivedTotal;
            }
        }
    }

    /**
     * Object key of one archive batch.
     *
     * <p>Includes a second precision stamp so two batches of the same pass, and two passes of the same day,
     * never overwrite one another - an overwritten archive is a silently lost audit trail.
     *
     * @param horizon retention horizon of this pass
     * @return object storage key under the configured prefix
     */
    private String objectKeyOf(LocalDateTime horizon) {
        return properties.getOpenApi().getAuditArchivePrefix()
                + horizon.format(DATE_FORMAT) + "/"
                + LocalDateTime.now().format(STAMP_FORMAT) + OBJECT_SUFFIX;
    }

    /**
     * Serialises one batch as gzipped JSON lines.
     *
     * <p>One JSON document per line rather than one array: a line oriented file can be read back with a
     * streaming reader and appended to, which an array cannot.
     *
     * @param batch rows to archive
     * @return gzip compressed payload
     */
    private byte[] gzipOf(List<ApiAuditLog> batch) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            for (ApiAuditLog row : batch) {
                gzip.write(JsonUtil.toJson(row).getBytes(StandardCharsets.UTF_8));
                gzip.write(LINE_BREAK.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IllegalStateException("api audit archive could not be compressed", e);
        }
        return compressed.toByteArray();
    }
}
