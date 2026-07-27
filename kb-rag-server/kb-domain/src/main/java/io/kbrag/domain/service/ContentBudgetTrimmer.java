package io.kbrag.domain.service;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies the {@code max_content_length} budget of an open API call, requirement section 4.8.
 *
 * <p><b>Whole units are kept or dropped, never cut.</b> The requirement fixes the unit of the budget as the
 * returned node (the parent chunk when two level splitting is on) because half a passage is not usable
 * material for a generation step, and because cutting the text would break the correspondence between the
 * returned content and the score and evidence that describe it.
 *
 * <p>The list arrives ranked, so taking a prefix is the same thing as "dropping by score" - the lowest
 * ranked node is always the one that goes. A first node longer than the entire budget is kept: returning an
 * empty result for a budget the caller chose too small would hide the answer instead of trimming it.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ContentBudgetTrimmer {

    /**
     * Number of leading nodes that fit into the budget.
     *
     * @param contentLengths content length of every node, in rank order
     * @param maxContentLength total budget in characters, {@code null} or non positive disables trimming
     * @return how many nodes to keep, always at least one when the list is not empty
     */
    public int keepCount(List<Integer> contentLengths, Integer maxContentLength) {
        if (CollectionUtils.isEmpty(contentLengths)) {
            return 0;
        }
        if (maxContentLength == null || maxContentLength <= 0) {
            return contentLengths.size();
        }
        int used = 0;
        int kept = 0;
        for (Integer length : contentLengths) {
            int cost = length == null ? 0 : length;
            if (kept > 0 && used + cost > maxContentLength) {
                break;
            }
            used += cost;
            kept++;
        }
        return kept;
    }
}
