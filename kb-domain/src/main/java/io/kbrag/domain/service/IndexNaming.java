package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
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
 * @author owlzhangfq@gmail.com
 */
@Component
public class IndexNaming {

    private static final String NAME_SEPARATOR = "_";

    /** Prefix of every physical index and alias. */
    private static final String NAME_PREFIX = "kb";

    /**
     * Builds the physical name of the vector index.
     *
     * @param kbId             knowledge base business id
     * @param embeddingSegment embedding version segment, {@code none} in zero key mode
     * @return physical index or collection name
     */
    public String vectorPhysicalName(String kbId, String embeddingSegment) {
        return physicalName(kbId, embeddingSegment);
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
        String segment = engine == VectorEngine.MILVUS ? KbConstants.EMBEDDING_SEGMENT_BM25 : embeddingSegment;
        return physicalName(kbId, segment);
    }

    /**
     * Builds the alias of an engine for a knowledge base.
     *
     * @param kbId   knowledge base business id
     * @param engine engine the alias belongs to
     * @return alias name
     */
    public String alias(String kbId, VectorEngine engine) {
        return NAME_PREFIX + NAME_SEPARATOR + normalizeKbId(kbId) + NAME_SEPARATOR + engine.code();
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

    private String physicalName(String kbId, String embeddingSegment) {
        return NAME_PREFIX + NAME_SEPARATOR + normalizeKbId(kbId)
                + NAME_SEPARATOR + embeddingSegment
                + NAME_SEPARATOR + KbConstants.SNAPSHOT_SEGMENT_V1;
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
