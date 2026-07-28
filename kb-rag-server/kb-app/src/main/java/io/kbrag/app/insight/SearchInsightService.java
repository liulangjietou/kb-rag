package io.kbrag.app.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.util.HashUtil;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.InsightSource;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.QueryDigestFactory;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records and reports the search insight rows, the M10 contract section 2.2: one row per online
 * retrieval, aggregated into the "what are people searching for that the corpus cannot serve" report.
 *
 * <p><b>Recorded at the API boundary, never inside {@code RetrievalService}.</b> Evaluation runs and
 * the release gate reuse the same retrieval pipeline; a hook inside the pipeline would pollute the
 * report with offline traffic that no user ever typed. The two boundaries that call
 * {@link #recordAsync(InsightRecord)} are the console debug search and the open API.
 *
 * <p><b>Written after the call, off the call's thread.</b> The exact {@code ApiAuditService}
 * discipline: an insight row is bookkeeping about a search that already answered, so the write is
 * asynchronous and its failure is logged, never rethrown.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchInsightService {

    /** Rows of the top zero hit query group listing. */
    private static final int TOP_QUERY_LIMIT = 10;

    /** Days the statistics window covers when the caller names no bounds. */
    private static final int DEFAULT_STATS_WINDOW_DAYS = 7;

    /** Collapses runs of whitespace during query normalization. */
    private static final String WHITESPACE_RUN = "\\s+";

    /** Single space the whitespace runs collapse into. */
    private static final String SINGLE_SPACE = " ";

    private final SearchInsightMapper searchInsightMapper;
    private final QueryDigestFactory queryDigestFactory;
    private final BizIdGenerator bizIdGenerator;
    private final KbProperties properties;

    /**
     * Queues one insight row per searched knowledge base.
     *
     * @param record everything known about the finished retrieval
     */
    @Async(AsyncConfig.AUDIT_EXECUTOR)
    public void recordAsync(InsightRecord record) {
        try {
            record(record);
        } catch (Exception e) {
            log.error("search insight rows not written, errorCode={}, source={}",
                    ErrorCode.INTERNAL_ERROR, record.getSource(), e);
        }
    }

    /**
     * Writes one insight row per searched knowledge base.
     *
     * <p>A multi knowledge base open API call produces one row per base rather than one row total:
     * the report is read per knowledge base, and a call that searched three bases is three chances
     * to discover a content gap. The counts describe the whole call, which the {@code request_id}
     * shared by the sibling rows makes visible.
     *
     * @param record everything known about the finished retrieval
     * @return persisted rows, empty when recording is disabled or no base was searched
     */
    public List<SearchInsight> record(InsightRecord record) {
        if (!properties.getInsight().isEnabled() || CollectionUtils.isEmpty(record.getKbIds())) {
            return List.of();
        }
        String digest = queryDigestFactory.digest(record.getQuery(),
                properties.getOpenApi().getQueryDigestMaxLength());
        String hash = queryHashOf(record.getQuery());
        int resultCount = record.getResultCount() == null ? 0 : record.getResultCount();
        String degraded = CollectionUtils.isEmpty(record.getDegraded())
                ? null : JsonUtil.toJson(record.getDegraded());
        List<SearchInsight> rows = new ArrayList<>(record.getKbIds().size());
        for (String kbId : record.getKbIds()) {
            SearchInsight row = new SearchInsight();
            row.setInsightId(bizIdGenerator.searchInsightId());
            row.setKbId(kbId);
            row.setSource(record.getSource());
            row.setQueryDigest(digest);
            row.setQueryHash(hash);
            row.setResultCount(resultCount);
            row.setTopScore(record.getTopScore());
            row.setZeroHit(resultCount == 0);
            row.setDegraded(degraded);
            row.setRequestId(record.getRequestId());
            searchInsightMapper.insert(row);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Grouping key of the miss report: SHA-256 of the trimmed, lower cased query with every
     * whitespace run collapsed to one space.
     *
     * <p>Exactly the equivalences a human applies when reading two queries aloud - case and spacing -
     * and nothing more. Stemming or synonym folding would merge questions a content author must see
     * apart.
     *
     * @param query raw query, {@code null} treated as empty
     * @return lower case hexadecimal hash
     */
    public String queryHashOf(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase()
                .replaceAll(WHITESPACE_RUN, SINGLE_SPACE);
        return HashUtil.sha256Hex(normalized);
    }

    /**
     * Pages the insight rows of one knowledge base, newest first.
     *
     * @param kbId    knowledge base business id
     * @param zeroHit optional zero hit filter
     * @param from    optional inclusive lower bound of the call time
     * @param to      optional inclusive upper bound of the call time
     * @param page    one based page number
     * @param size    page size
     * @return paged rows
     */
    public IPage<SearchInsight> list(String kbId, Boolean zeroHit, LocalDateTime from, LocalDateTime to,
                                     long page, long size) {
        return searchInsightMapper.selectPage(new Page<>(page, size),
                filter(kbId, zeroHit, from, to).orderByDesc(SearchInsight::getId));
    }

    /**
     * Aggregates the content gap report of one knowledge base, the M10 contract section 2.2.
     *
     * <p>Computed over the filtered rows rather than kept as running counters, the same reasoning as
     * the audit statistics: the table already holds every fact, and a second incrementally maintained
     * set of totals would be one more thing that can disagree with it.
     *
     * @param kbId knowledge base business id
     * @param from optional inclusive lower bound, defaults to seven days back
     * @param to   optional inclusive upper bound
     * @return totals plus the top zero hit query groups
     */
    public InsightStats stats(String kbId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime effectiveFrom = from != null ? from
                : LocalDate.now().minusDays(DEFAULT_STATS_WINDOW_DAYS).atStartOfDay();
        List<SearchInsight> rows = searchInsightMapper.selectList(
                filter(kbId, null, effectiveFrom, to).orderByAsc(SearchInsight::getId));
        if (CollectionUtils.isEmpty(rows)) {
            return new InsightStats(0L, 0L, 0.0d, 0L, List.of());
        }
        long zeroHits = 0;
        long degradedCalls = 0;
        Map<String, TopQueryAccumulator> groups = new LinkedHashMap<>();
        for (SearchInsight row : rows) {
            if (Boolean.TRUE.equals(row.getZeroHit())) {
                zeroHits++;
                groups.computeIfAbsent(row.getQueryHash(), key -> new TopQueryAccumulator())
                        .absorb(row);
            }
            if (row.getDegraded() != null && !row.getDegraded().isBlank()) {
                degradedCalls++;
            }
        }
        List<TopZeroHitQuery> top = groups.values().stream()
                .sorted(Comparator.comparingLong(TopQueryAccumulator::getCount).reversed())
                .limit(TOP_QUERY_LIMIT)
                .map(TopQueryAccumulator::toView)
                .toList();
        return new InsightStats(rows.size(), zeroHits, (double) zeroHits / rows.size(),
                degradedCalls, top);
    }

    /**
     * Daily retention pass.
     *
     * <p>Failures are logged and never rethrown: the scheduler has no caller to report to, and one
     * skipped pass costs table size until the next one, which is not a correctness problem.
     */
    @Scheduled(cron = "${kb.insight.cleanup-cron:0 45 3 * * *}")
    public void scheduledCleanup() {
        try {
            int purged = purgeExpired();
            if (purged > 0) {
                log.info("search insight cleanup pass finished, purgedRows={}", purged);
            }
        } catch (Exception e) {
            log.error("search insight cleanup pass failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
        }
    }

    /**
     * Removes every row older than the retention window, one bounded batch at a time.
     *
     * @return rows removed by this pass
     */
    public int purgeExpired() {
        int batchSize = Math.max(1, properties.getInsight().getCleanupBatchSize());
        LocalDateTime horizon = LocalDate.now()
                .minusDays(properties.getInsight().getRetentionDays())
                .atStartOfDay();
        int purgedTotal = 0;
        while (true) {
            int purged = searchInsightMapper.purgeExpired(horizon, batchSize);
            purgedTotal += purged;
            if (purged < batchSize) {
                return purgedTotal;
            }
        }
    }

    private LambdaQueryWrapper<SearchInsight> filter(String kbId, Boolean zeroHit,
                                                     LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<SearchInsight> wrapper = new LambdaQueryWrapper<SearchInsight>()
                .eq(SearchInsight::getKbId, kbId);
        if (zeroHit != null) {
            wrapper.eq(SearchInsight::getZeroHit, zeroHit);
        }
        if (from != null) {
            wrapper.ge(SearchInsight::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(SearchInsight::getCreatedAt, to);
        }
        return wrapper;
    }

    /**
     * Everything one recording point knows about a finished retrieval.
     */
    @Getter
    @Builder
    @ToString(exclude = "query")
    public static class InsightRecord {

        /** API boundary the call entered through. */
        private final InsightSource source;

        /** Knowledge bases the call searched, one row each. */
        private final List<String> kbIds;

        /** Raw query; digested and hashed before anything is stored. */
        private final String query;

        /** Nodes the call returned. */
        private final Integer resultCount;

        /** Score of the first node, {@code null} on zero hits. */
        private final Double topScore;

        /** Degradation markers of the call. */
        private final List<String> degraded;

        /** Correlation id of the call. */
        private final String requestId;
    }

    /**
     * Content gap report of one knowledge base over one window.
     *
     * @param total            recorded retrievals
     * @param zeroHitCount     retrievals that returned nothing
     * @param zeroHitRate      {@code zeroHitCount / total}, 0 when the window is empty
     * @param degradedCount    retrievals that carried at least one degradation marker
     * @param topZeroHitQueries most frequent zero hit query groups, largest first
     */
    public record InsightStats(long total, long zeroHitCount, double zeroHitRate, long degradedCount,
                               List<TopZeroHitQuery> topZeroHitQueries) {
    }

    /**
     * One zero hit query group of the report.
     *
     * @param queryDigest masked digest of the newest row of the group
     * @param count       zero hit rows in the group
     * @param lastAt      time of the newest row of the group
     */
    public record TopZeroHitQuery(String queryDigest, long count, LocalDateTime lastAt) {
    }

    /**
     * Running aggregate of one query hash group; rows arrive oldest first, so the last absorbed row
     * is the newest and its digest is the one the report shows.
     */
    @Getter
    private static class TopQueryAccumulator {

        private String queryDigest;
        private long count;
        private LocalDateTime lastAt;

        private void absorb(SearchInsight row) {
            queryDigest = row.getQueryDigest();
            lastAt = row.getCreatedAt();
            count++;
        }

        private TopZeroHitQuery toView() {
            return new TopZeroHitQuery(queryDigest, count, lastAt);
        }
    }
}
