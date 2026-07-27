package io.kbrag.app.retrieval;

import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.model.AppIndexSnapshot;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * The physical indices one knowledge base is searched in, replacing its aliases for the duration of a call.
 *
 * <p>Set only by a released application version out of its frozen snapshot, requirement section 4.7. Every
 * other caller - the console debug page, a chat preview, an evaluation run, a beta call against a test version -
 * leaves it unset and is served through the live aliases.
 *
 * @param fulltextIndex physical name the BM25 route addresses
 * @param vectorIndex   physical name the vector route addresses, {@code null} when the base has no vector index
 *
 * @author owlzhangfq@gmail.com
 */
public record RetrievalIndexOverride(String fulltextIndex, String vectorIndex) {

    /**
     * Builds the override of one knowledge base from its frozen snapshots.
     *
     * <p><b>Which snapshot serves which route is read off the engine, not off today's configuration.</b> A
     * Qdrant snapshot can only be a vector index and an Elasticsearch snapshot is always the BM25 one; in lite
     * mode the single Elasticsearch snapshot serves both routes, exactly as the live index does. Consulting the
     * configured engine instead would mis-route every historical version after a lite to full migration.
     *
     * @param snapshots snapshots of one knowledge base, one per engine
     * @return override, or {@code null} when the snapshots hold no full text index to search
     */
    public static RetrievalIndexOverride of(List<AppIndexSnapshot> snapshots) {
        if (CollectionUtils.isEmpty(snapshots)) {
            return null;
        }
        String fulltextIndex = null;
        String qdrantIndex = null;
        for (AppIndexSnapshot snapshot : snapshots) {
            if (VectorEngine.from(snapshot.engine()) == VectorEngine.QDRANT) {
                qdrantIndex = snapshot.physicalIndexName();
            } else {
                fulltextIndex = snapshot.physicalIndexName();
            }
        }
        if (fulltextIndex == null) {
            return null;
        }
        return new RetrievalIndexOverride(fulltextIndex,
                qdrantIndex == null ? fulltextIndex : qdrantIndex);
    }
}
