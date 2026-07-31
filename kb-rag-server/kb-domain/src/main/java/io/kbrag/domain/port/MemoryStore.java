package io.kbrag.domain.port;

import io.kbrag.domain.model.MemoryDoc;
import io.kbrag.domain.model.MemoryHit;
import io.kbrag.domain.model.MemorySearchQuery;

import java.util.List;

/**
 * Search index over memory nodes, the M19 contract.
 *
 * <p>The index is a disposable copy of MySQL: every mutation here mirrors one already committed to
 * the source of truth, and a lost index is rebuilt by replaying live nodes, never the other way
 * round. Expired nodes are excluded at query time by the implementation.
 *
 * @author owlzhangfq@gmail.com
 */
public interface MemoryStore {

    /**
     * Writes or overwrites the search copy of one node.
     *
     * @param doc node to index, keyed by its node id
     */
    void upsert(MemoryDoc doc);

    /**
     * Removes the search copy of one node; removing an absent node is a no-op.
     *
     * @param nodeId node business id
     */
    void delete(String nodeId);

    /**
     * Removes every search copy of one library, the cleanup of a library deletion.
     *
     * @param libraryId library business id
     */
    void deleteByLibrary(String libraryId);

    /**
     * Removes every search copy produced by one fragment rule, the cleanup of a rule deletion.
     *
     * @param libraryId library business id
     * @param ruleId    fragment rule business id
     */
    void deleteByRule(String libraryId, String ruleId);

    /**
     * Recalls the top scoring live nodes of one entity.
     *
     * @param query filters, text and optional vector
     * @return hits ordered by descending score, at most {@code topK}
     */
    List<MemoryHit> search(MemorySearchQuery query);
}
