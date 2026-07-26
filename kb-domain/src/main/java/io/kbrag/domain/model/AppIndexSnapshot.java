package io.kbrag.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One physical index a released application version froze, requirement section 4.7 "index snapshot".
 *
 * <p>An element of the {@code index_snapshots} column of {@code t_kb_app_version}: one row per knowledge
 * base and per engine, because a full mode deployment snapshots two indices for the same base - the BM25
 * index and the vector collection - and a call has to address the right one per route.
 *
 * <p><b>The physical name is stored, never recomputed.</b> Recomputing it would rebuild the name from the
 * embedding segment of <em>today</em>, and an embedding model switch after the release would then point a
 * historical version at an index it never contained.
 *
 * @param kbId              knowledge base business id the snapshot belongs to
 * @param engine            engine code, {@code es} or {@code milvus}
 * @param physicalIndexName physical index or collection name of the snapshot, addressed directly since a
 *                          snapshot carries no alias
 *
 * @author owlzhangfq@gmail.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppIndexSnapshot(
        @JsonProperty("kb_id") String kbId,
        @JsonProperty("engine") String engine,
        @JsonProperty("physical_index_name") String physicalIndexName) {
}
