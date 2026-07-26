package io.kbrag.app.annotation;

import io.kbrag.app.index.ChunkEmbedder;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.FixedLengthTextSplitter;
import io.kbrag.domain.service.SimpleTokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the change pipeline of the four manual chunk operations: the validation gate that protects the
 * order and the two level structure, the resulting order numbers and parent links, and which operations
 * are allowed to cost an embedding call.
 *
 * @author owlzhangfq@gmail.com
 */
class ChunkAnnotationServiceTest {

    private static final String KB_ID = "kb_1";
    private static final String DOC_ID = "doc_1";
    private static final String VERSION_ID = "dv_1";
    private static final String OTHER_VERSION_ID = "dv_2";
    private static final String PARENT_ID = "ck_parent";

    private ChunkMapper chunkMapper;
    private ChunkIndexWriter chunkIndexWriter;
    private ChunkEmbedder chunkEmbedder;
    private KnowledgeBaseService knowledgeBaseService;
    private AnnotationRecorder annotationRecorder;
    private BizIdGenerator bizIdGenerator;
    private ChunkAnnotationService service;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(ChunkMapper.class);
        chunkIndexWriter = mock(ChunkIndexWriter.class);
        chunkEmbedder = mock(ChunkEmbedder.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        annotationRecorder = mock(AnnotationRecorder.class);
        bizIdGenerator = mock(BizIdGenerator.class);

        when(chunkEmbedder.isConfigured()).thenReturn(true);
        when(chunkEmbedder.embed(anyList())).thenReturn(java.util.Map.of());
        when(knowledgeBaseService.indexConfigOf(anyString())).thenReturn(indexConfig());
        when(bizIdGenerator.chunkId()).thenAnswer(invocation -> "ck_new_" + System.nanoTime());

        service = new ChunkAnnotationService(chunkMapper, new ChunkTextHasher(), chunkIndexWriter,
                chunkEmbedder, knowledgeBaseService, annotationRecorder, bizIdGenerator,
                new FixedLengthTextSplitter(new SimpleTokenEstimator()));
    }

    @Test
    void shouldEmbedAgainWhenTheTextChanges() {
        givenChunk(chunk("ck_1", 0, null, "the original passage"));
        givenNoChildren();

        service.edit("ck_1", "the corrected passage");

        // The text is what an embedding describes, so an edit has to pay for a new one and overwrite both
        // engine copies, otherwise the BM25 route keeps matching the old wording.
        verify(chunkEmbedder, times(1)).embed(anyList());
        verify(chunkIndexWriter, times(1)).write(eq(KB_ID), anyList(), any());
    }

    @Test
    void shouldNotEmbedAgainWhenOnlyTheRetrievalSwitchChanges() {
        givenChunk(chunk("ck_1", 0, null, "the original passage"));
        givenNoChildren();

        service.toggle("ck_1", false);

        // Nothing about the text changed, so the vector is still correct; only the engine side flag moves.
        verify(chunkEmbedder, never()).embed(anyList());
        verify(chunkIndexWriter, never()).write(anyString(), anyList(), any());
        verify(chunkIndexWriter, times(1)).syncEnabled(KB_ID, List.of("ck_1"), false);
    }

    @Test
    void shouldLeaveAnEditThatChangesNothingAlone() {
        givenChunk(chunk("ck_1", 0, null, "unchanged passage"));

        service.edit("ck_1", "unchanged  passage ");

        // The normalised digest is what downstream compares, and whitespace is normalised away, so this is
        // the same chunk and re-embedding it would buy nothing.
        verify(chunkEmbedder, never()).embed(anyList());
        verify(annotationRecorder, never()).record(any(), any(), anyString(), any());
    }

    @Test
    void shouldCascadeADisabledParentOntoItsChildren() {
        Chunk parent = chunk(PARENT_ID, 0, null, "the whole section");
        givenChunk(parent);
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_child_a", 0, PARENT_ID, "first passage"),
                chunk("ck_child_b", 1, PARENT_ID, "second passage")));

        List<String> changed = service.toggle(PARENT_ID, false);

        assertEquals(List.of(PARENT_ID, "ck_child_a", "ck_child_b"), changed);
        verify(chunkIndexWriter, times(1)).syncEnabled(KB_ID, changed, false);
        // Three rows updated: the parent and both children.
        verify(chunkMapper, times(3)).updateById(any(Chunk.class));
    }

    @Test
    void shouldSkipTheChildrenThatAreAlreadyInTheRequestedState() {
        Chunk parent = chunk(PARENT_ID, 0, null, "the whole section");
        givenChunk(parent);
        Chunk alreadyDisabled = chunk("ck_child_a", 0, PARENT_ID, "first passage");
        alreadyDisabled.setEnabled(0);
        when(chunkMapper.selectList(any())).thenReturn(List.of(alreadyDisabled,
                chunk("ck_child_b", 1, PARENT_ID, "second passage")));

        List<String> changed = service.toggle(PARENT_ID, false);

        assertEquals(List.of(PARENT_ID, "ck_child_b"), changed);
    }

    @Test
    void shouldRejectAMergeOfFewerThanTwoChunks() {
        BizException failure = assertThrows(BizException.class, () -> service.merge(List.of("ck_1")));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        verify(chunkMapper, never()).insert(any(Chunk.class));
    }

    @Test
    void shouldRejectAMergeAcrossTwoDocumentVersions() {
        Chunk first = chunk("ck_1", 0, null, "first");
        Chunk second = chunk("ck_2", 1, null, "second");
        second.setDocumentVersionId(OTHER_VERSION_ID);
        when(chunkMapper.selectList(any())).thenReturn(List.of(first, second));

        BizException failure = assertThrows(BizException.class,
                () -> service.merge(List.of("ck_1", "ck_2")));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("one document version"));
    }

    @Test
    void shouldRejectAMergeOfChunksThatAreNotConsecutive() {
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_1", 0, null, "first"), chunk("ck_3", 2, null, "third")));

        BizException failure = assertThrows(BizException.class,
                () -> service.merge(List.of("ck_1", "ck_3")));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("consecutive"));
    }

    @Test
    void shouldRejectAMergeOfChunksWithDifferentParents() {
        Chunk first = chunk("ck_1", 0, PARENT_ID, "first");
        Chunk second = chunk("ck_2", 1, "ck_other_parent", "second");
        when(chunkMapper.selectList(any())).thenReturn(List.of(first, second));

        BizException failure = assertThrows(BizException.class,
                () -> service.merge(List.of("ck_1", "ck_2")));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("same parent"));
    }

    @Test
    void shouldConcatenateInOrderAndKeepTheLowestSeqAndTheSharedParent() {
        // Deliberately submitted out of order: the concatenation has to follow the position in the
        // document, not the order the console happened to send the identifiers in.
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("ck_2", 4, PARENT_ID, "second half"),
                        chunk("ck_1", 3, PARENT_ID, "first half")))
                .thenReturn(List.of());

        Chunk merged = service.merge(List.of("ck_2", "ck_1"));

        assertEquals("first half\nsecond half", merged.getContent());
        assertEquals(3, merged.getSeq());
        assertEquals(PARENT_ID, merged.getParentId());
        assertEquals(VERSION_ID, merged.getDocumentVersionId());
        assertEquals(EmbeddingStatus.PENDING, merged.getEmbeddingStatus());
        verify(chunkIndexWriter, times(1)).write(eq(KB_ID), anyList(), any());
    }

    @Test
    void shouldRejectSplitOffsetsThatAreEmpty() {
        givenChunk(chunk("ck_1", 0, null, "a passage long enough to cut"));

        BizException failure = assertThrows(BizException.class, () -> service.split("ck_1", List.of()));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
    }

    @Test
    void shouldRejectSplitOffsetsOutsideTheText() {
        givenChunk(chunk("ck_1", 0, null, "short"));

        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> service.split("ck_1", List.of(99))).getErrorCode());
        // Zero would produce an empty first part, and the length itself an empty last one.
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> service.split("ck_1", List.of(0))).getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> service.split("ck_1", List.of(5))).getErrorCode());
    }

    @Test
    void shouldRejectSplitOffsetsThatDoNotAscend() {
        givenChunk(chunk("ck_1", 0, null, "a passage long enough to cut"));

        BizException failure = assertThrows(BizException.class,
                () -> service.split("ck_1", List.of(10, 4)));

        assertEquals(ErrorCode.INVALID_PARAM, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("ascending"));
    }

    @Test
    void shouldProduceOneMorePartThanOffsetsWithConsecutiveSeqAndTheSameParent() {
        givenChunk(chunk("ck_1", 7, PARENT_ID, "abcdefghij"));
        givenNoChildren();
        List<Chunk> inserted = capturingInserts();

        List<Chunk> parts = service.split("ck_1", List.of(3, 6));

        assertEquals(3, parts.size());
        assertEquals(List.of("abc", "def", "ghij"), parts.stream().map(Chunk::getContent).toList());
        assertEquals(List.of(7, 8, 9), parts.stream().map(Chunk::getSeq).toList());
        assertTrue(parts.stream().allMatch(part -> PARENT_ID.equals(part.getParentId())));
        assertTrue(parts.stream().allMatch(part -> VERSION_ID.equals(part.getDocumentVersionId())));
        assertEquals(3, inserted.size());
        // Distinct identifiers: the parts are new chunks, not a renaming of the source.
        assertNotEquals(parts.get(0).getChunkId(), parts.get(1).getChunkId());
        // The tail of the level is pushed back first, then the parts are inserted into the gap.
        verify(chunkMapper, times(1)).update(eq(null), any());

        verify(knowledgeBaseService, times(1)).removeChunks(eq(KB_ID), any());
    }

    @Test
    void shouldRecutTheChildrenOfASplitParentInsteadOfIndexingTheParent() {
        givenChunk(chunk(PARENT_ID, 0, null, "abcdefghij"));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
                chunk("ck_child_a", 0, PARENT_ID, "abcde"),
                chunk("ck_child_b", 1, PARENT_ID, "fghij")));
        List<Chunk> inserted = capturingInserts();

        List<Chunk> parts = service.split(PARENT_ID, List.of(5));

        assertEquals(2, parts.size());
        // Parents stay out of the engines, so only the freshly cut children are written; both new parents
        // and their children are inserted.
        assertTrue(inserted.size() > parts.size());
        assertTrue(inserted.stream().anyMatch(chunk -> chunk.getParentId() != null));
        verify(chunkIndexWriter, times(2)).write(eq(KB_ID), anyList(), any());
        // The obsolete child generation and the source parent are both removed.
        verify(knowledgeBaseService, times(2)).removeChunks(eq(KB_ID), any());
    }

    @Test
    void shouldSkipEmbeddingEntirelyInAZeroKeyDeployment() {
        when(chunkEmbedder.isConfigured()).thenReturn(false);
        givenChunk(chunk("ck_1", 0, null, "the original passage"));
        givenNoChildren();

        Chunk edited = service.edit("ck_1", "the corrected passage");

        assertEquals(EmbeddingStatus.SKIPPED, edited.getEmbeddingStatus());
        // Still written to the full text engine: the BM25 route must show the corrected wording.
        verify(chunkIndexWriter, times(1)).write(eq(KB_ID), anyList(), any());
    }

    private void givenChunk(Chunk chunk) {
        when(chunkMapper.selectOne(any())).thenReturn(chunk);
    }

    private void givenNoChildren() {
        when(chunkMapper.selectList(any())).thenReturn(List.of());
    }

    /**
     * Captures the rows the service inserts, so the produced generation can be inspected.
     *
     * @return live list the inserts are appended to
     */
    private List<Chunk> capturingInserts() {
        List<Chunk> inserted = new ArrayList<>();
        when(chunkMapper.insert(any(Chunk.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });
        return inserted;
    }

    private KbIndexConfig indexConfig() {
        KbIndexConfig config = new KbIndexConfig();
        config.setChunkMaxTokens(600);
        config.setChunkOverlap(100);
        ParentChildParams parentChild = new ParentChildParams();
        parentChild.setEnabled(true);
        parentChild.setParentMaxTokens(1200);
        parentChild.setChildMaxTokens(400);
        parentChild.setChildOverlap(50);
        config.setParentChild(parentChild);
        return config;
    }

    private Chunk chunk(String chunkId, int seq, String parentId, String content) {
        Chunk chunk = new Chunk();
        chunk.setId((long) seq + 1);
        chunk.setChunkId(chunkId);
        chunk.setKbId(KB_ID);
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(VERSION_ID);
        chunk.setContent(content);
        chunk.setChunkTextHash(new ChunkTextHasher().hash(content));
        chunk.setParentId(parentId);
        chunk.setSeq(seq);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setEnabled(1);
        chunk.setEmbeddingStatus(EmbeddingStatus.DONE);
        return chunk;
    }
}
