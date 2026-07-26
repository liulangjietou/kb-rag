package io.kbrag.app.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.mapper.ChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Decides what a parent chunk containing disabled children is allowed to return.
 *
 * <p>The one phase semantics of the requirement return the whole parent and merely report which of its
 * children are excluded. Cutting the disabled passage out of the parent text would need the character
 * offset of every child inside its parent, and an edited child invalidates that offset immediately, so
 * the caller is told instead of being lied to.
 *
 * <p>A knowledge base that must never let excluded text reach a caller flips the switch, and the parent
 * disappears entirely. That is a deliberate trade: the answer loses the surrounding context rather than
 * risking one disabled sentence inside it.
 *
 * <p>The lookup asks MySQL rather than reading the recall set, and that is the point: a disabled child is
 * filtered out engine side, so it is precisely the thing a recall can never report.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisabledChildVisibility {

    private static final int DISABLED = 0;

    private final ChunkMapper chunkMapper;

    /**
     * Loads the disabled children of the parents about to be returned.
     *
     * @param parentIds parent chunk ids of the merged units, ignored when empty
     * @return disabled child ids per parent, parents without any absent from the map
     */
    public Map<String, List<String>> disabledChildrenOf(List<String> parentIds) {
        if (CollectionUtils.isEmpty(parentIds)) {
            return Map.of();
        }
        List<Chunk> disabled = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getParentId, parentIds)
                .eq(Chunk::getEnabled, DISABLED)
                .orderByAsc(Chunk::getSeq));
        if (CollectionUtils.isEmpty(disabled)) {
            return Map.of();
        }
        Map<String, List<String>> byParent = new HashMap<>();
        for (Chunk child : disabled) {
            byParent.computeIfAbsent(child.getParentId(), key -> new ArrayList<>()).add(child.getChunkId());
        }
        return byParent;
    }

    /**
     * Applies one knowledge base policy to the merged units.
     *
     * @param units                        merged units in ranking order
     * @param disabledChildrenByParent     disabled child ids per parent chunk id
     * @param hideParentWithDisabledChild  knowledge base switch
     * @return units that may be returned, together with what has to be reported on each of them
     */
    public Visibility apply(List<RetrievalUnit> units, Map<String, List<String>> disabledChildrenByParent,
                            boolean hideParentWithDisabledChild) {
        return apply(units, disabledChildrenByParent, unit -> hideParentWithDisabledChild);
    }

    /**
     * Applies the policy of each unit's own knowledge base to the merged units.
     *
     * <p>The switch is a knowledge base setting, so a cross base result list carries units governed by
     * different answers to the same question. Resolving it per unit is what keeps a base that never asked
     * to hide anything from losing results because another base in the same application did - collapsing
     * the two into one flag would let one operator's policy silently rewrite another's.
     *
     * @param units                        merged units in ranking order
     * @param disabledChildrenByParent     disabled child ids per parent chunk id
     * @param hideParent                   tells whether a unit's knowledge base hides such a parent
     * @return units that may be returned, together with what has to be reported on each of them
     */
    public Visibility apply(List<RetrievalUnit> units, Map<String, List<String>> disabledChildrenByParent,
                            Predicate<RetrievalUnit> hideParent) {
        if (CollectionUtils.isEmpty(units) || disabledChildrenByParent.isEmpty()) {
            return new Visibility(units == null ? List.of() : units, Map.of());
        }
        List<RetrievalUnit> kept = new ArrayList<>(units.size());
        Map<String, List<String>> reported = new HashMap<>();
        int hidden = 0;
        for (RetrievalUnit unit : units) {
            List<String> disabledChildren = unit.isParent()
                    ? disabledChildrenByParent.get(unit.getUnitId()) : null;
            if (CollectionUtils.isEmpty(disabledChildren)) {
                kept.add(unit);
                continue;
            }
            if (hideParent.test(unit)) {
                hidden++;
                continue;
            }
            reported.put(unit.getUnitId(), disabledChildren);
            kept.add(unit);
        }
        if (hidden > 0) {
            log.info("parents hidden because they contain disabled children, hidden={}, units={}",
                    hidden, units.size());
        }
        return new Visibility(kept, reported);
    }

    /**
     * Result of applying the policy.
     *
     * @param units                    units that may be returned, in the incoming order
     * @param disabledChildIdsByUnit   disabled child ids to report per returned unit
     */
    public record Visibility(List<RetrievalUnit> units, Map<String, List<String>> disabledChildIdsByUnit) {
    }
}
