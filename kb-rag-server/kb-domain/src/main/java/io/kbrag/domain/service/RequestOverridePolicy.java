package io.kbrag.domain.service;

import io.kbrag.common.exception.BizException;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer five of the configuration model: which parameters an open API call may override, requirement
 * section 5 "request level override, white list only".
 *
 * <p><b>Why the list is this short.</b> The four allowed parameters shape the response, not the retrieval
 * behaviour the release gate validated. Everything else - the version and disabled filters,
 * {@code recall_top_k}, the fusion strategy and the rerank settings - is frozen in the version snapshot
 * because a caller able to change them would make the gate's verdict false for the traffic actually served.
 * That is the entire reason this is a deny by default list and not a validation of ranges.
 *
 * <p>An unknown or forbidden key is rejected rather than ignored: silently dropping it would let an agent
 * believe its tuning took effect and draw wrong conclusions from the results.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class RequestOverridePolicy {

    /** Number of returned nodes. */
    public static final String TOP_N = "top_n";

    /** Absolute score threshold. */
    public static final String SCORE_THRESHOLD = "score_threshold";

    /** Narrowing metadata predicate. */
    public static final String METADATA_FILTER = "metadata_filter";

    /** Character budget of the returned content. */
    public static final String MAX_CONTENT_LENGTH = "max_content_length";

    private static final Set<String> WHITELIST =
            Set.of(TOP_N, SCORE_THRESHOLD, METADATA_FILTER, MAX_CONTENT_LENGTH);

    /**
     * The white list itself, exposed so the API layer can document it in an error message.
     *
     * @return allowed override keys
     */
    public Set<String> whitelist() {
        return WHITELIST;
    }

    /**
     * Fast-fails a call that tries to override something outside the white list.
     *
     * <p>The single gate for this rule; no stage downstream re-checks it, and no stage downstream is allowed
     * to read a parameter from the request that did not pass through here.
     *
     * @param presentedKeys keys the request actually carried, {@code null} or empty is accepted
     * @throws BizException with {@code INVALID_PARAM} when any key is not on the white list
     */
    public void validate(Set<String> presentedKeys) {
        if (CollectionUtils.isEmpty(presentedKeys)) {
            return;
        }
        List<String> rejected = new ArrayList<>();
        for (String key : presentedKeys) {
            if (!WHITELIST.contains(key)) {
                rejected.add(key);
            }
        }
        if (!rejected.isEmpty()) {
            throw BizException.invalidParam("请求级参数覆盖仅允许 " + String.join("/", sortedWhitelist())
                    + "，以下参数不可覆盖：" + String.join(",", rejected));
        }
    }

    /**
     * Override keys of one call, in a deterministic order, for the audit row.
     *
     * @param presentedKeys keys the request carried
     * @return white listed keys actually present, ordered as the white list documents them
     */
    public List<String> appliedKeys(Set<String> presentedKeys) {
        if (CollectionUtils.isEmpty(presentedKeys)) {
            return List.of();
        }
        List<String> applied = new ArrayList<>();
        for (String key : sortedWhitelist()) {
            if (presentedKeys.contains(key)) {
                applied.add(key);
            }
        }
        return applied;
    }

    /**
     * White list in a fixed, documented order.
     *
     * @return ordered white list
     */
    private Set<String> sortedWhitelist() {
        return new LinkedHashSet<>(List.of(TOP_N, SCORE_THRESHOLD, METADATA_FILTER, MAX_CONTENT_LENGTH));
    }
}
