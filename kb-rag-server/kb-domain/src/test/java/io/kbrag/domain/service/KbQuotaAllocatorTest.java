package io.kbrag.domain.service;

import io.kbrag.domain.model.KbRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the quota split of requirement section 4.7 "quota total": the budget is one global fifty, the
 * division rounds down, and the remainder lands on the heaviest base rather than being dropped.
 *
 * @author owlzhangfq@gmail.com
 */
class KbQuotaAllocatorTest {

    private static final int TOTAL = 50;

    private final KbQuotaAllocator allocator = new KbQuotaAllocator();

    @Test
    void shouldRoundDownAndGiveTheRemainderToTheHeaviestBase() {
        // 50 * 2/4 = 25, 50 * 1/4 = 12.5 -> 12 twice, so 49 of 50 are placed and the leftover goes to the
        // base weighted 2 - the only distribution that cannot contradict the operator's stated preference.
        Map<String, Integer> quotas = allocator.allocate(
                List.of(new KbRef("kb_a", 2), new KbRef("kb_b", 1), new KbRef("kb_c", 1)), TOTAL);

        assertEquals(26, quotas.get("kb_a"));
        assertEquals(12, quotas.get("kb_b"));
        assertEquals(12, quotas.get("kb_c"));
        assertEquals(TOTAL, quotas.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void shouldSplitThreeToOneIntoThirtyEightAndTwelve() {
        // The acceptance case of the milestone: 50 * 3/4 = 37.5 -> 37 and 50 * 1/4 = 12.5 -> 12, remainder
        // one to the base weighted 3.
        Map<String, Integer> quotas = allocator.allocate(
                List.of(new KbRef("kb_a", 3), new KbRef("kb_b", 1)), TOTAL);

        assertEquals(38, quotas.get("kb_a"));
        assertEquals(12, quotas.get("kb_b"));
    }

    @Test
    void shouldBreakAWeightTieOnDeclarationOrder() {
        // Equal weights leave two units over; "the heaviest base" has no unique answer, so declaration
        // order decides. Determinism matters more than evenness here: an allocation that varied between
        // calls would make an evaluation run irreproducible.
        Map<String, Integer> quotas = allocator.allocate(
                List.of(new KbRef("kb_a", 1), new KbRef("kb_b", 1), new KbRef("kb_c", 1)), TOTAL);

        assertEquals(18, quotas.get("kb_a"));
        assertEquals(16, quotas.get("kb_b"));
        assertEquals(16, quotas.get("kb_c"));
        assertEquals(TOTAL, quotas.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void shouldGiveASingleBaseTheWholeBudget() {
        Map<String, Integer> quotas = allocator.allocate(List.of(new KbRef("kb_a", 7)), TOTAL);

        assertEquals(1, quotas.size());
        assertEquals(TOTAL, quotas.get("kb_a"));
    }

    @Test
    void shouldTreatAMissingOrNonPositiveWeightAsOne() {
        // A base an operator linked but never weighted must still be searched; a zero quota would remove it
        // from the answer without saying so.
        Map<String, Integer> quotas = allocator.allocate(
                List.of(new KbRef("kb_a", null), new KbRef("kb_b", 0), new KbRef("kb_c", 2)), TOTAL);

        assertEquals(12, quotas.get("kb_a"));
        assertEquals(12, quotas.get("kb_b"));
        assertEquals(26, quotas.get("kb_c"));
    }

    @Test
    void shouldAllocateNothingWithoutBasesOrBudget() {
        assertTrue(allocator.allocate(List.of(), TOTAL).isEmpty());
        assertTrue(allocator.allocate(List.of(new KbRef("kb_a", 1), new KbRef("kb_b", 1)), 0).isEmpty());
    }

    @Test
    void shouldNeverExceedTheGlobalBudgetHoweverManyBasesAreLinked() {
        // The whole point of section 4.7: fifteen bases taking fifty each would multiply the cost of the
        // rerank stage by fifteen and break the latency promise the cap exists for.
        List<KbRef> refs = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            refs.add(new KbRef("kb_" + i, 1));
        }

        Map<String, Integer> quotas = allocator.allocate(refs, TOTAL);

        assertEquals(15, quotas.size());
        assertEquals(TOTAL, quotas.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(8, quotas.get("kb_0"));
        assertEquals(3, quotas.get("kb_14"));
    }
}
