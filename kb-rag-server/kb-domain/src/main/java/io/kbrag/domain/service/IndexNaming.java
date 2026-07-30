package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.constant.BuiltinTenants;
import io.kbrag.domain.enums.VectorEngine;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Builds the three segment physical index names and the aliases every read and write goes through.
 *
 * <p>Naming rules of the contract:
 * <ul>
 *   <li>physical name {@code kb_{kbId}_{embeddingSegment}_{snapshotSegment}}, the snapshot segment
 *       being fixed to {@code v1} for M1;</li>
 *   <li>embedding segment {@code none} when no embedding provider is configured, {@code bm25} for
 *       the full mode Elasticsearch index which only serves BM25, and the abbreviated embedding
 *       model version everywhere else;</li>
 *   <li>alias {@code kb_{kbId}_{engine}}.</li>
 * </ul>
 *
 * <p>Giving the full mode Elasticsearch index a {@code bm25} segment instead of the embedding
 * version is deliberate: swapping the embedding model must not force a rebuild of the full text
 * index, which would also reset the BM25 scoring baseline and the tokenizer dictionary.
 *
 * <p>Since M16 a knowledge base outside the default tenant carries a tenant segment right after the
 * prefix: {@code kb_{tenantSegment}_{kbId}_...}. The default tenant keeps the historical names - that
 * is the compatibility red line, injecting a segment there would orphan every alias an upgraded
 * deployment has. The one argument overloads without a tenant exist for exactly that default tenant
 * behaviour and for the M1-M15 call sites and tests written against it.
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class IndexNaming {

    private static final String NAME_SEPARATOR = "_";

    /** Prefix of every physical index and alias. */
    private static final String NAME_PREFIX = "kb";

    /** Engine independent marker of the multimodal index, the M14 contract section 6.2. */
    private static final String MULTIMODAL_MARKER = "mm";

    /**
     * Builds the physical name of the vector index.
     *
     * @param kbId             knowledge base business id
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return physical index or collection name
     */
    public String vectorPhysicalName(String kbId, String embeddingSegment) {
        return vectorPhysicalName(null, kbId, embeddingSegment);
    }

    /**
     * Builds the physical name of the vector index of a tenant scoped knowledge base.
     *
     * @param tenantId         owning tenant business id, the default tenant adds no segment
     * @param kbId             knowledge base business id
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return physical index or collection name
     */
    public String vectorPhysicalName(String tenantId, String kbId, String embeddingSegment) {
        return physicalName(tenantId, kbId, embeddingSegment, KbConstants.SNAPSHOT_SEGMENT_V1);
    }

    /**
     * Builds the physical name of a release snapshot, requirement section 4.7 "index snapshot".
     *
     * <p>Only the snapshot segment changes: a snapshot of the full mode Elasticsearch index keeps the
     * {@code bm25} segment and a snapshot of a Qdrant collection keeps its embedding segment, so the name
     * still tells which engine and which embedding space the data belongs to. The sequence is knowledge
     * base level, which is what makes both engines of one release share the same {@code sN}.
     *
     * @param kbId             knowledge base business id
     * @param embeddingSegment embedding segment of the source index, carried over unchanged
     * @param sequence         knowledge base level snapshot sequence, one based
     * @return physical snapshot index or collection name
     */
    public String snapshotPhysicalName(String kbId, String embeddingSegment, int sequence) {
        return snapshotPhysicalName(null, kbId, embeddingSegment, sequence);
    }

    /**
     * Builds the physical name of a release snapshot of a tenant scoped knowledge base.
     *
     * @param tenantId         owning tenant business id, the default tenant adds no segment
     * @param kbId             knowledge base business id
     * @param embeddingSegment embedding segment of the source index, carried over unchanged
     * @param sequence         knowledge base level snapshot sequence, one based
     * @return physical snapshot index or collection name
     */
    public String snapshotPhysicalName(String tenantId, String kbId, String embeddingSegment,
                                       int sequence) {
        return physicalName(tenantId, kbId, embeddingSegment,
                KbConstants.SNAPSHOT_SEGMENT_PREFIX + sequence);
    }

    /**
     * Builds the snapshot segment of a sequence number.
     *
     * @param sequence knowledge base level snapshot sequence, one based
     * @return snapshot segment, {@code s1} for the first snapshot
     */
    public String snapshotSegment(int sequence) {
        return KbConstants.SNAPSHOT_SEGMENT_PREFIX + sequence;
    }

    /**
     * Reads the sequence number back out of a snapshot segment.
     *
     * <p>The single parser of the segment, so the sequence derivation of the next snapshot cannot disagree
     * with the way a name was built. A segment that is not a snapshot segment yields zero, which is what
     * makes the live {@code v1} rows contribute nothing to the maximum.
     *
     * @param snapshotSegment snapshot segment of a registry row, {@code null} tolerated
     * @return sequence number, zero when the segment is not a snapshot segment
     */
    public int snapshotSequenceOf(String snapshotSegment) {
        if (snapshotSegment == null
                || !snapshotSegment.startsWith(KbConstants.SNAPSHOT_SEGMENT_PREFIX)) {
            return 0;
        }
        String digits = snapshotSegment.substring(KbConstants.SNAPSHOT_SEGMENT_PREFIX.length());
        if (!digits.matches("^\\d+$")) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /**
     * Builds the physical name of the full text index.
     *
     * <p>In lite mode the same index also carries the vector field, so it keeps the embedding
     * segment; in full mode it only serves BM25 and takes the {@code bm25} segment.
     *
     * @param kbId             knowledge base business id
     * @param engine           configured vector engine
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return physical index name
     */
    public String fulltextPhysicalName(String kbId, VectorEngine engine, String embeddingSegment) {
        return fulltextPhysicalName(null, kbId, engine, embeddingSegment);
    }

    /**
     * Builds the physical name of the full text index of a tenant scoped knowledge base.
     *
     * @param tenantId         owning tenant business id, the default tenant adds no segment
     * @param kbId             knowledge base business id
     * @param engine           configured vector engine
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return physical index name
     */
    public String fulltextPhysicalName(String tenantId, String kbId, VectorEngine engine,
                                       String embeddingSegment) {
        return physicalName(tenantId, kbId, fulltextEmbeddingSegment(engine, embeddingSegment),
                KbConstants.SNAPSHOT_SEGMENT_V1);
    }

    /**
     * Embedding segment the full text index carries.
     *
     * <p>Exposed because the snapshot path needs the very same answer for the snapshot name: in full mode
     * the Elasticsearch index only serves BM25 and takes {@code bm25}, in lite mode it also carries the
     * vector and therefore keeps the embedding segment.
     *
     * @param engine           configured vector engine
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return embedding segment of the full text index name
     */
    public String fulltextEmbeddingSegment(VectorEngine engine, String embeddingSegment) {
        return engine == VectorEngine.QDRANT ? KbConstants.EMBEDDING_SEGMENT_BM25 : embeddingSegment;
    }

    /**
     * Builds the alias of an engine for a knowledge base.
     *
     * @param kbId   knowledge base business id
     * @param engine engine the alias belongs to
     * @return alias name
     */
    public String alias(String kbId, VectorEngine engine) {
        return alias(null, kbId, engine);
    }

    /**
     * Builds the alias of an engine for a tenant scoped knowledge base.
     *
     * @param tenantId owning tenant business id, the default tenant adds no segment
     * @param kbId     knowledge base business id
     * @param engine   engine the alias belongs to
     * @return alias name
     */
    public String alias(String tenantId, String kbId, VectorEngine engine) {
        return NAME_PREFIX + tenantInfix(tenantId) + NAME_SEPARATOR + normalizeKbId(kbId)
                + NAME_SEPARATOR + engine.code();
    }

    /**
     * Builds the alias of the multimodal index, the M14 contract section 6.2.
     *
     * <p>Engine independent on purpose: the multimodal vectors live in whichever engine the deployment
     * uses for vectors - a Qdrant collection in full mode, an Elasticsearch dense_vector index in lite
     * mode - but the retrieval side addresses them through one alias without caring which, exactly as
     * it does for the text vector route.
     *
     * @param kbId knowledge base business id
     * @return multimodal alias
     */
    public String multimodalAlias(String kbId) {
        return multimodalAlias(null, kbId);
    }

    /**
     * Builds the alias of the multimodal index of a tenant scoped knowledge base.
     *
     * @param tenantId owning tenant business id, the default tenant adds no segment
     * @param kbId     knowledge base business id
     * @return multimodal alias
     */
    public String multimodalAlias(String tenantId, String kbId) {
        return NAME_PREFIX + tenantInfix(tenantId) + NAME_SEPARATOR + normalizeKbId(kbId)
                + NAME_SEPARATOR + MULTIMODAL_MARKER;
    }

    /**
     * Builds the physical name of the multimodal index, the M14 contract section 6.2.
     *
     * <p>Carries the multimodal marker in place of the snapshot segment so its name never collides with
     * a text index of the same embedding segment, and keeps the embedding segment so a multimodal model
     * switch produces a new physical name and forces a clean rebuild rather than mixing dimensions.
     *
     * @param kbId             knowledge base business id
     * @param embeddingSegment abbreviated multimodal model segment
     * @return physical multimodal index or collection name
     */
    public String multimodalPhysicalName(String kbId, String embeddingSegment) {
        return multimodalPhysicalName(null, kbId, embeddingSegment);
    }

    /**
     * Builds the physical name of the multimodal index of a tenant scoped knowledge base.
     *
     * @param tenantId         owning tenant business id, the default tenant adds no segment
     * @param kbId             knowledge base business id
     * @param embeddingSegment abbreviated multimodal model segment
     * @return physical multimodal index or collection name
     */
    public String multimodalPhysicalName(String tenantId, String kbId, String embeddingSegment) {
        return physicalName(tenantId, kbId, embeddingSegment, MULTIMODAL_MARKER);
    }

    /**
     * Abbreviates an embedding model name into the version segment of an index name.
     *
     * <p>Each dash separated token contributes its first character, except a short token made of a
     * single optional letter followed by digits which is kept whole, so
     * {@code text-embedding-v4} becomes {@code tev4} and {@code bge-m3} becomes {@code bm3}.
     *
     * @param modelName embedding model name, blank yields the zero key segment
     * @return embedding version segment
     */
    public String embeddingSegment(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return KbConstants.EMBEDDING_SEGMENT_NONE;
        }
        String[] tokens = modelName.toLowerCase(Locale.ROOT).split("[-_.]");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.matches("^[a-z]?\\d+$")) {
                builder.append(token);
            } else {
                builder.append(token.charAt(0));
            }
        }
        return builder.length() == 0 ? KbConstants.EMBEDDING_SEGMENT_NONE : builder.toString();
    }

    private String physicalName(String tenantId, String kbId, String embeddingSegment,
                                String snapshotSegment) {
        return NAME_PREFIX + tenantInfix(tenantId) + NAME_SEPARATOR + normalizeKbId(kbId)
                + NAME_SEPARATOR + embeddingSegment
                + NAME_SEPARATOR + snapshotSegment;
    }

    /**
     * Tenant infix of a name, empty for the default tenant.
     *
     * <p>The default tenant deliberately contributes nothing: its names must stay byte identical to
     * the pre-M16 ones or an upgraded deployment loses every alias it has. The value of the segment on
     * the other tenants is operational legibility - a physical index attached to the wrong tenant is
     * visible in the name itself - not collision avoidance, the kbId is already globally unique.
     *
     * @param tenantId owning tenant business id, {@code null} means the default tenant
     * @return {@code _segment} infix, or an empty string for the default tenant
     */
    private String tenantInfix(String tenantId) {
        if (BuiltinTenants.isDefault(tenantId)) {
            return "";
        }
        String prefix = KbConstants.TENANT_ID_PREFIX + KbConstants.ID_SEPARATOR;
        String value = tenantId.startsWith(prefix) ? tenantId.substring(prefix.length()) : tenantId;
        return NAME_SEPARATOR + value.toLowerCase(Locale.ROOT);
    }

    /**
     * Strips the business id prefix and lower cases the remainder so the resulting index name stays
     * legal for Elasticsearch and readable, instead of repeating {@code kb_kb_}.
     *
     * @param kbId knowledge base business id
     * @return identifying part of the business id
     */
    private String normalizeKbId(String kbId) {
        String prefix = KbConstants.KB_ID_PREFIX + KbConstants.ID_SEPARATOR;
        String value = kbId.startsWith(prefix) ? kbId.substring(prefix.length()) : kbId;
        return value.toLowerCase(Locale.ROOT);
    }
}
