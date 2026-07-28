package io.kbrag.app.metrics;

import io.kbrag.domain.enums.InsightSource;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Business metrics facade, the M13 contract section 3.1.
 *
 * <p><b>One method per business fact, no registry leaking into callers.</b> The instrumented
 * services state what happened - a search finished, a task completed, a call was rejected - and
 * this class alone decides metric names, tag keys and tag values. That keeps the naming scheme in
 * one file and keeps an unbounded label (a kb id, a key id, a raw query) from ever becoming a tag:
 * every value below comes from a bounded enum or a boolean.
 *
 * <p><b>Recording never disturbs the business path.</b> Micrometer counters and timers are in
 * memory operations that do not fail; a {@code null} enum from a defensive caller drops the sample
 * instead of throwing, because losing one data point is cheaper than failing the call it describes.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KbMetrics {

    /** Timer of one online retrieval, the same two API boundaries the M10 insight records. */
    static final String SEARCH = "kb.search";

    /** Counter of one finished asynchronous task. */
    static final String TASK_COMPLETED = "kb.task.completed";

    /** Counter of one open API call the filter rejected before any controller ran. */
    static final String OPENAPI_REJECTED = "kb.openapi.rejected";

    /** Counter of one web source sync outcome, the M12 four state result. */
    static final String WEBSOURCE_SYNC = "kb.websource.sync";

    static final String TAG_SOURCE = "source";
    static final String TAG_ZERO_HIT = "zero_hit";
    static final String TAG_DEGRADED = "degraded";
    static final String TAG_TYPE = "type";
    static final String TAG_STATUS = "status";
    static final String TAG_ERROR_CODE = "error_code";

    static final String STATUS_SUCCESS = "success";
    static final String STATUS_FAILED = "failed";

    private final MeterRegistry registry;

    public KbMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records one online retrieval.
     *
     * @param source    API boundary the call entered through
     * @param latencyMs wall time of the retrieval stage in milliseconds
     * @param resultCount nodes the call returned
     * @param degraded  degradation markers of the call, empty or {@code null} on a clean run
     */
    public void recordSearch(InsightSource source, long latencyMs, int resultCount, List<String> degraded) {
        if (source == null) {
            return;
        }
        Timer.builder(SEARCH)
                .tag(TAG_SOURCE, lower(source.name()))
                .tag(TAG_ZERO_HIT, String.valueOf(resultCount == 0))
                .tag(TAG_DEGRADED, String.valueOf(CollectionUtils.isNotEmpty(degraded)))
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records one finished asynchronous task.
     *
     * @param taskType task category
     * @param success  {@code true} for a successful completion
     */
    public void recordTaskCompleted(TaskType taskType, boolean success) {
        if (taskType == null) {
            return;
        }
        Counter.builder(TASK_COMPLETED)
                .tag(TAG_TYPE, lower(taskType.name()))
                .tag(TAG_STATUS, success ? STATUS_SUCCESS : STATUS_FAILED)
                .register(registry)
                .increment();
    }

    /**
     * Records one open API call rejected by the authentication filter.
     *
     * @param errorCode classified rejection cause, a bounded {@code ErrorCode} name
     */
    public void recordOpenApiRejected(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return;
        }
        Counter.builder(OPENAPI_REJECTED)
                .tag(TAG_ERROR_CODE, errorCode)
                .register(registry)
                .increment();
    }

    /**
     * Records the outcome of one web source sync.
     *
     * @param status four state outcome written onto the registration row
     */
    public void recordWebSourceSync(WebSourceFetchStatus status) {
        if (status == null) {
            return;
        }
        Counter.builder(WEBSOURCE_SYNC)
                .tag(TAG_STATUS, lower(status.name()))
                .register(registry)
                .increment();
    }

    private String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
