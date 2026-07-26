package io.kbrag.app.retrieval;

import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.IndexAliasManager;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Decides, per knowledge base of one call, which indices the recall routes address and which document versions
 * they may see, requirement sections 4.4 "version visibility set" and 4.7 "index snapshot".
 *
 * <p><b>The single place the three calling contexts are told apart.</b> Spreading the decision over the callers
 * is how the two halves of it - the index and the visibility set - would eventually be resolved from different
 * contexts, which is precisely the defect that made a rollback recall nothing:
 * <ul>
 *   <li>a <b>released</b> version with a frozen snapshot searches its snapshot indices under its frozen
 *       visibility set, so it keeps answering out of the corpus its release gate measured;</li>
 *   <li>every other caller - a beta call against a test version, a chat preview, the console debug page, an
 *       evaluation run - searches the live aliases under the current active versions, because all of them exist
 *       to observe the corpus as it is now;</li>
 *   <li>a version <b>released before snapshots existed</b> carries nothing frozen and lands in the second
 *       branch. That is a historical data shape and not a fault, so it carries no degradation marker - reporting
 *       one would make every legacy call look broken.</li>
 * </ul>
 *
 * <p><b>The existence check is the one guard.</b> A frozen name whose index was deleted out of band - a restore
 * from an older backup, an operator cleaning up by hand - would otherwise fail the call outright. Instead the
 * call is served from the live alias and says so through {@code snapshot_index_missing}, because a released
 * version answering from today's corpus is degraded but useful, while an error is neither. It is also the reason
 * the check lives here and not in the search path: falling back has to switch the visibility set at the same
 * time, and only this class holds both.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalIndexContextResolver {

    private final IndexAliasManager indexAliasManager;
    private final ActiveVersionResolver activeVersionResolver;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;

    /**
     * Resolves the retrieval context of one knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param command request parameters, read for the snapshot overrides
     * @return context to search this base with
     */
    public IndexContext resolve(String kbId, RetrievalCommand command) {
        RetrievalIndexOverride override = overrideOf(kbId, command);
        List<String> frozenVersionIds = frozenVersionIdsOf(kbId, command);
        if (override == null || CollectionUtils.isEmpty(frozenVersionIds)) {
            return liveContext(kbId, false);
        }
        if (!snapshotPresent(override)) {
            log.error("frozen snapshot index is missing, falling back to the live alias, errorCode={}, "
                            + "kbId={}, fulltextIndex={}, vectorIndex={}",
                    ErrorCode.INTERNAL_ERROR, kbId, override.fulltextIndex(), override.vectorIndex());
            return liveContext(kbId, true);
        }
        return new IndexContext(override.fulltextIndex(), override.vectorIndex(), frozenVersionIds,
                false, true);
    }

    /**
     * Tells whether every snapshot index a context needs still exists.
     *
     * <p>Both routes are checked even in lite mode, where they name the same index: the answer is identical and
     * one extra head request per released call is cheaper than a branch on the deployment shape.
     *
     * @param override frozen indices of one knowledge base
     * @return {@code true} when the engines still hold them
     */
    private boolean snapshotPresent(RetrievalIndexOverride override) {
        if (!fulltextStore.indexExists(override.fulltextIndex())) {
            return false;
        }
        return override.vectorIndex() == null || vectorStore.indexExists(override.vectorIndex());
    }

    /**
     * Context that searches the live aliases under the current active versions.
     *
     * @param kbId              knowledge base business id
     * @param snapshotDegraded  {@code true} when this fallback replaced a snapshot that should have existed
     * @return live context
     */
    private IndexContext liveContext(String kbId, boolean snapshotDegraded) {
        return new IndexContext(indexAliasManager.fulltextAlias(kbId), indexAliasManager.vectorAlias(kbId),
                activeVersionResolver.activeVersionIds(kbId), snapshotDegraded, false);
    }

    private RetrievalIndexOverride overrideOf(String kbId, RetrievalCommand command) {
        Map<String, RetrievalIndexOverride> overrides = command.getIndexOverride();
        return overrides == null ? null : overrides.get(kbId);
    }

    private List<String> frozenVersionIdsOf(String kbId, RetrievalCommand command) {
        Map<String, List<String>> frozen = command.getVisibleVersionIdsOverride();
        return frozen == null ? null : frozen.get(kbId);
    }

    /**
     * What one knowledge base of a call is searched with.
     *
     * @param fulltextIndex     alias or physical index the BM25 route addresses
     * @param vectorIndex       alias or physical index the vector route addresses, {@code null} in zero key mode
     * @param visibleVersionIds document versions the mandatory engine side filter admits
     * @param snapshotDegraded  {@code true} when a frozen snapshot was expected and turned out to be missing
     * @param snapshotBound     {@code true} when this context reads an immutable snapshot rather than the live
     *                          index, which is what switches the self healing of orphaned engine hits off
     */
    public record IndexContext(String fulltextIndex, String vectorIndex, List<String> visibleVersionIds,
                               boolean snapshotDegraded, boolean snapshotBound) {
    }
}
