package io.kbrag.app.openapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.ApiAuditLog;
import io.kbrag.domain.entity.ApiKey;
import io.kbrag.domain.enums.TargetStage;
import io.kbrag.domain.mapper.ApiAuditLogMapper;
import io.kbrag.domain.mapper.ApiKeyMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Records and queries the outbound call audit trail, requirement section 4.8.
 *
 * <p><b>Written after the call, off the call's thread.</b> An audit row is evidence about a call that already
 * happened; making the caller wait for it would trade response time for nothing, and letting its failure
 * propagate would turn a bookkeeping problem into a failed search. Both directions are therefore closed: the
 * write is asynchronous and its exception is logged, never rethrown.
 *
 * <p><b>Rejections are recorded too.</b> An authorisation failure is the single most interesting event an
 * audit trail holds, so a scope violation produces a row with its error code and no version.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiAuditService {

    /** Endpoint literal of the search call. */
    public static final String ENDPOINT_SEARCH = "search";

    /** Endpoint literal of the chat call. */
    public static final String ENDPOINT_CHAT = "chat";

    private final ApiAuditLogMapper apiAuditLogMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final QueryDigestFactory queryDigestFactory;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Queues one audit row.
     *
     * @param record everything known about the finished call
     */
    @Async(AsyncConfig.AUDIT_EXECUTOR)
    public void recordAsync(AuditRecord record) {
        try {
            record(record);
        } catch (Exception e) {
            log.error("open api audit row not written, errorCode={}, keyId={}, endpoint={}",
                    ErrorCode.INTERNAL_ERROR, record.getKeyId(), record.getEndpoint(), e);
        }
    }

    /**
     * Writes one audit row.
     *
     * @param record everything known about the finished call
     * @return persisted row
     */
    public ApiAuditLog record(AuditRecord record) {
        ApiAuditLog row = new ApiAuditLog();
        row.setAuditLogId(bizIdGenerator.apiAuditLogId());
        row.setKeyId(record.getKeyId());
        row.setAppId(record.getAppId());
        row.setAppVersionId(record.getAppVersionId());
        row.setTargetStage(record.getTargetStage());
        row.setEndpoint(record.getEndpoint());
        row.setQueryDigest(queryDigestFactory.digest(record.getQuery(),
                properties.getOpenApi().getQueryDigestMaxLength()));
        row.setHitDocIds(CollectionUtils.isEmpty(record.getHitDocIds())
                ? null : JsonUtil.toJson(record.getHitDocIds()));
        row.setLatencyMs(record.getLatencyMs());
        row.setDegraded(CollectionUtils.isEmpty(record.getDegraded())
                ? null : JsonUtil.toJson(record.getDegraded()));
        row.setOverrideKeys(CollectionUtils.isEmpty(record.getOverrideKeys())
                ? null : JsonUtil.toJson(record.getOverrideKeys()));
        row.setErrorCode(record.getErrorCode());
        row.setRequestId(record.getRequestId());
        apiAuditLogMapper.insert(row);
        return row;
    }

    /**
     * Pages the audit trail, newest first.
     *
     * @param keyId       optional API key filter
     * @param targetStage optional version stage filter
     * @param from        optional inclusive lower bound of the call time
     * @param to          optional inclusive upper bound of the call time
     * @param page        one based page number
     * @param size        page size
     * @return paged rows
     */
    public IPage<ApiAuditLog> query(String keyId, TargetStage targetStage, LocalDateTime from,
                                    LocalDateTime to, long page, long size) {
        List<String> visibleKeyIds = visibleKeyIds(keyId);
        if (visibleKeyIds.isEmpty()) {
            return new Page<>(page, size);
        }
        return apiAuditLogMapper.selectPage(new Page<>(page, size),
                filter(visibleKeyIds, targetStage, from, to).orderByDesc(ApiAuditLog::getId));
    }

    /**
     * Aggregates the call volume of one filter, requirement section 4.8 "call volume statistics".
     *
     * <p>Computed over the filtered rows rather than kept as running counters: an audit table already holds
     * every fact, and a second, incrementally maintained set of totals would be one more thing that can
     * disagree with it.
     *
     * @param keyId       optional API key filter
     * @param targetStage optional version stage filter
     * @param from        optional inclusive lower bound of the call time
     * @param to          optional inclusive upper bound of the call time
     * @return totals of the filtered rows
     */
    public AuditStats stats(String keyId, TargetStage targetStage, LocalDateTime from, LocalDateTime to) {
        List<String> visibleKeyIds = visibleKeyIds(keyId);
        if (visibleKeyIds.isEmpty()) {
            return new AuditStats(0L, 0.0d, 0L, 0L);
        }
        List<ApiAuditLog> rows = apiAuditLogMapper.selectList(
                filter(visibleKeyIds, targetStage, from, to));
        if (CollectionUtils.isEmpty(rows)) {
            return new AuditStats(0L, 0.0d, 0L, 0L);
        }
        long degraded = 0;
        long errors = 0;
        long latencySum = 0;
        for (ApiAuditLog row : rows) {
            if (row.getDegraded() != null && !row.getDegraded().isBlank() && !"[]".equals(row.getDegraded())) {
                degraded++;
            }
            if (row.getErrorCode() != null && !row.getErrorCode().isBlank()) {
                errors++;
            }
            latencySum += row.getLatencyMs() == null ? 0 : row.getLatencyMs();
        }
        return new AuditStats(rows.size(), (double) latencySum / rows.size(), degraded, errors);
    }

    /**
     * Resolves which keys the current caller may read the trail of.
     *
     * <p><b>This is the tenant boundary of the whole audit screen.</b> {@code t_kb_api_audit_log} is a
     * subordinate of {@code t_kb_api_key} and carries no {@code tenant_id}, so nothing in its own
     * statement can tell one tenant's calls from another's. {@code t_kb_api_key} is a fenced root, so
     * reading the key ids through it yields exactly the keys of the caller's tenant - and an empty
     * answer, which the callers turn into an empty page rather than into "no filter at all".
     *
     * <p>Materialising the ids into an {@code in} clause rather than writing a tenant column onto the
     * audit table follows the M16 contract section 1.1①: a subordinate reaches its tenant through its root,
     * and a second tenant column would be a second fact that can disagree. The list is bounded by the
     * number of keys a tenant holds, which is a console-managed handful.
     *
     * <p>A named key of another tenant comes back empty here, so it reads exactly like a key that does
     * not exist - the same answer a filter value that matches nothing has always given.
     *
     * @param keyId optional key filter named by the request
     * @return key ids to constrain the trail to, empty when the caller may read none
     */
    private List<String> visibleKeyIds(String keyId) {
        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<ApiKey>().select(ApiKey::getKeyId);
        if (keyId != null && !keyId.isBlank()) {
            wrapper.eq(ApiKey::getKeyId, keyId);
        }
        return apiKeyMapper.selectList(wrapper).stream().map(ApiKey::getKeyId).toList();
    }

    private LambdaQueryWrapper<ApiAuditLog> filter(List<String> keyIds, TargetStage targetStage,
                                                   LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<ApiAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ApiAuditLog::getKeyId, keyIds);
        if (targetStage != null) {
            wrapper.eq(ApiAuditLog::getTargetStage, targetStage);
        }
        if (from != null) {
            wrapper.ge(ApiAuditLog::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(ApiAuditLog::getCreatedAt, to);
        }
        return wrapper;
    }

    /**
     * Everything one audit row records about a finished call.
     */
    @Getter
    @Builder
    @ToString(exclude = "query")
    public static class AuditRecord {

        /** Calling API key business id. */
        private final String keyId;

        /** Application the call named, {@code null} when it named none that exists. */
        private final String appId;

        /** Application version that served the call, {@code null} when the call was rejected. */
        private final String appVersionId;

        /** Version stage served, {@code null} when the call was rejected. */
        private final TargetStage targetStage;

        /** Endpoint literal. */
        private final String endpoint;

        /** Raw query; masked and truncated before it is stored. */
        private final String query;

        /** Document ids of the returned nodes. */
        private final List<String> hitDocIds;

        /** Server side duration in milliseconds. */
        private final Integer latencyMs;

        /** Degradation markers of the call. */
        private final List<String> degraded;

        /** Request level override keys that were applied. */
        private final List<String> overrideKeys;

        /** Business error code when the call was rejected. */
        private final String errorCode;

        /** Correlation id of the call. */
        private final String requestId;
    }

    /**
     * Call volume totals of one filter.
     *
     * @param totalCalls   rows matching the filter
     * @param avgLatencyMs mean server side duration
     * @param degradedCalls rows that carried at least one degradation marker
     * @param errorCalls   rows that were rejected with a business error code
     */
    public record AuditStats(long totalCalls, double avgLatencyMs, long degradedCalls, long errorCalls) {
    }
}
