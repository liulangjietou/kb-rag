package io.kbrag.domain.service;

import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.enums.VectorEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the three segment index naming rules, including the deliberate asymmetry that keeps the full
 * mode full text index out of the embedding version scheme.
 *
 * @author owlzhangfq@gmail.com
 */
class IndexNamingTest {

    private static final String KB_ID = "kb_7f3a91c2e5d84b60";
    private static final String KB_SUFFIX = "7f3a91c2e5d84b60";

    private final IndexNaming naming = new IndexNaming();

    @Test
    void shouldAbbreviateEmbeddingModelNames() {
        assertEquals("tev4", naming.embeddingSegment("text-embedding-v4"));
        assertEquals("bm3", naming.embeddingSegment("bge-m3"));
        assertEquals(KbConstants.EMBEDDING_SEGMENT_NONE, naming.embeddingSegment(""));
        assertEquals(KbConstants.EMBEDDING_SEGMENT_NONE, naming.embeddingSegment(null));
    }

    @Test
    void shouldBuildVectorPhysicalName() {
        assertEquals("kb_" + KB_SUFFIX + "_tev4_v1", naming.vectorPhysicalName(KB_ID, "tev4"));
    }

    @Test
    void shouldGiveTheFullModeFulltextIndexTheBm25Segment() {
        assertEquals("kb_" + KB_SUFFIX + "_bm25_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.QDRANT, "tev4"));
    }

    @Test
    void shouldNameSnapshotsByASequenceAndKeepTheEmbeddingSegment() {
        assertEquals("kb_" + KB_SUFFIX + "_tev4_s1", naming.snapshotPhysicalName(KB_ID, "tev4", 1));
        assertEquals("kb_" + KB_SUFFIX + "_tev4_s2", naming.snapshotPhysicalName(KB_ID, "tev4", 2));
        // Full mode: the BM25 index keeps its bm25 segment in a snapshot too, so a snapshot name still says
        // which engine and which scoring baseline the data belongs to.
        assertEquals("kb_" + KB_SUFFIX + "_bm25_s3",
                naming.snapshotPhysicalName(KB_ID, KbConstants.EMBEDDING_SEGMENT_BM25, 3));
        // Zero key mode keeps the none placeholder.
        assertEquals("kb_" + KB_SUFFIX + "_none_s1",
                naming.snapshotPhysicalName(KB_ID, KbConstants.EMBEDDING_SEGMENT_NONE, 1));
    }

    @Test
    void shouldReadTheSequenceBackOutOfASnapshotSegment() {
        assertEquals(1, naming.snapshotSequenceOf(naming.snapshotSegment(1)));
        assertEquals(42, naming.snapshotSequenceOf("s42"));
        // The live segment must contribute nothing to the maximum the next sequence is derived from.
        assertEquals(0, naming.snapshotSequenceOf(KbConstants.SNAPSHOT_SEGMENT_V1));
        assertEquals(0, naming.snapshotSequenceOf("snapshot"));
        assertEquals(0, naming.snapshotSequenceOf(null));
    }

    @Test
    void shouldReportWhichEmbeddingSegmentTheFulltextIndexCarries() {
        // Lite mode: one index serves both routes, so it keeps the embedding segment and a snapshot of it
        // carries the vector field along.
        assertEquals("tev4", naming.fulltextEmbeddingSegment(VectorEngine.ES, "tev4"));
        assertEquals(KbConstants.EMBEDDING_SEGMENT_BM25,
                naming.fulltextEmbeddingSegment(VectorEngine.QDRANT, "tev4"));
    }

    @Test
    void shouldKeepTheEmbeddingSegmentOnTheLiteFulltextIndex() {
        assertEquals("kb_" + KB_SUFFIX + "_tev4_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.ES, "tev4"));
    }

    @Test
    void shouldUseTheZeroKeySegmentWhenNoModelIsConfigured() {
        assertEquals("kb_" + KB_SUFFIX + "_none_v1",
                naming.fulltextPhysicalName(KB_ID, VectorEngine.ES, KbConstants.EMBEDDING_SEGMENT_NONE));
    }

    @Test
    void shouldBuildAliasPerEngine() {
        assertEquals("kb_" + KB_SUFFIX + "_es", naming.alias(KB_ID, VectorEngine.ES));
        assertEquals("kb_" + KB_SUFFIX + "_qdrant", naming.alias(KB_ID, VectorEngine.QDRANT));
    }
}
