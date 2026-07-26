package io.kbrag.domain.service;

import io.kbrag.domain.model.KbRef;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits the shared rerank candidate budget between the knowledge bases of one call, requirement
 * section 4.7 "quota total".
 *
 * <p><b>The budget is global, not per base.</b> The rerank protection of section 4.4 caps a call at
 * fifty candidates to keep the latency promise; letting N bases each contribute fifty would multiply
 * the cost of the stage by N and break exactly the promise the cap exists for. The weights therefore
 * divide one budget instead of scaling it.
 *
 * <p><b>Rounding goes down, the remainder goes to the heaviest base.</b> Rounding up would overshoot
 * the cap, and dropping the remainder would leave paid budget unused. Giving the leftover to the base
 * the operator weighted highest is the only distribution that cannot contradict the stated preference,
 * and declaration order breaks a tie so the same configuration always allocates identically - an
 * allocation that varies between calls would make an evaluation run irreproducible.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class KbQuotaAllocator {

    /**
     * Allocates the candidate budget.
     *
     * @param refs  linked knowledge bases in declaration order, weights already normalised
     * @param total shared candidate budget, non positive yields an empty allocation
     * @return quota per knowledge base id in declaration order, summing to {@code total}
     */
    public Map<String, Integer> allocate(List<KbRef> refs, int total) {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(refs) || total <= 0) {
            return quotas;
        }
        if (refs.size() == 1) {
            // A single base takes the whole budget, which is also what keeps the multi base path
            // numerically identical to the single base one it has to stay compatible with.
            quotas.put(refs.get(0).kbId(), total);
            return quotas;
        }

        int weightSum = 0;
        for (KbRef ref : refs) {
            weightSum += ref.effectiveWeight();
        }
        int allocated = 0;
        String heaviestKbId = refs.get(0).kbId();
        int heaviestWeight = refs.get(0).effectiveWeight();
        for (KbRef ref : refs) {
            int quota = total * ref.effectiveWeight() / weightSum;
            quotas.put(ref.kbId(), quota);
            allocated += quota;
            if (ref.effectiveWeight() > heaviestWeight) {
                heaviestWeight = ref.effectiveWeight();
                heaviestKbId = ref.kbId();
            }
        }
        int remainder = total - allocated;
        if (remainder > 0) {
            quotas.put(heaviestKbId, quotas.get(heaviestKbId) + remainder);
        }
        return quotas;
    }
}
