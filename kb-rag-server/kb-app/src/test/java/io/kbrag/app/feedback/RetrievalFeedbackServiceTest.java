package io.kbrag.app.feedback;

import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.enums.CaseSource;
import io.kbrag.domain.enums.FeedbackStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the feedback loop of the M10 contract section 2.1: a verdict is an event that always lands,
 * the owning document is resolved server side, and the only legal transitions are
 * {@code NEW -> CONVERTED} for a {@code GOOD} verdict and {@code NEW -> DISMISSED} for any.
 *
 * @author owlzhangfq@gmail.com
 */
class RetrievalFeedbackServiceTest {

    private static final String FEEDBACK_ID = "rfb_1";
    private static final String KB_ID = "kb_1";
    private static final String QUERY = "如何重置密码";
    private static final String CHUNK_ID = "ck_1";
    private static final String DOC_ID = "doc_1";
    private static final String DATASET_ID = "ds_1";
    private static final String CASE_ID = "case_1";

    private RetrievalFeedbackMapper retrievalFeedbackMapper;
    private ChunkMapper chunkMapper;
    private EvalDatasetService evalDatasetService;
    private RetrievalFeedbackService service;

    @BeforeEach
    void setUp() {
        retrievalFeedbackMapper = mock(RetrievalFeedbackMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        evalDatasetService = mock(EvalDatasetService.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.retrievalFeedbackId()).thenReturn(FEEDBACK_ID);
        service = new RetrievalFeedbackService(retrievalFeedbackMapper, chunkMapper,
                mock(SearchInsightMapper.class), evalDatasetService, bizIdGenerator);
    }

    @Test
    void shouldResolveTheOwningDocumentServerSide() {
        when(chunkMapper.selectOne(any())).thenReturn(chunk());

        RetrievalFeedback stored = service.record(KB_ID, QUERY, CHUNK_ID, FeedbackVerdict.GOOD);

        verify(retrievalFeedbackMapper).insert(any(RetrievalFeedback.class));
        assertEquals(FEEDBACK_ID, stored.getFeedbackId());
        assertEquals(DOC_ID, stored.getDocId());
        assertEquals(FeedbackVerdict.GOOD, stored.getVerdict());
        assertEquals(FeedbackStatus.NEW, stored.getStatus());
    }

    @Test
    void shouldAcceptFeedbackWhoseChunkWasAlreadyDeleted() {
        when(chunkMapper.selectOne(any())).thenReturn(null);

        RetrievalFeedback stored = service.record(KB_ID, QUERY, CHUNK_ID, FeedbackVerdict.BAD);

        // The signal may arrive after the chunk is gone; the query is still a fact worth keeping.
        verify(retrievalFeedbackMapper).insert(any(RetrievalFeedback.class));
        assertNull(stored.getDocId());
    }

    @Test
    void shouldConvertAGoodFeedbackThroughTheDebugPagePath() {
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(feedback(FeedbackVerdict.GOOD, FeedbackStatus.NEW));
        when(chunkMapper.selectOne(any())).thenReturn(chunk());
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(CASE_ID);
        when(evalDatasetService.collectFromRetrieval(eq(DATASET_ID), eq(QUERY), isNull(),
                eq(List.of(CHUNK_ID)), isNull(), eq(CaseSource.FEEDBACK))).thenReturn(evalCase);

        RetrievalFeedback converted = service.convert(FEEDBACK_ID, DATASET_ID);

        ArgumentCaptor<RetrievalFeedback> captor = ArgumentCaptor.forClass(RetrievalFeedback.class);
        verify(retrievalFeedbackMapper).updateById(captor.capture());
        assertEquals(FeedbackStatus.CONVERTED, captor.getValue().getStatus());
        assertEquals(CASE_ID, captor.getValue().getConvertedCaseId());
        assertEquals(CASE_ID, converted.getConvertedCaseId());
    }

    @Test
    void shouldRefuseToConvertABadVerdict() {
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(feedback(FeedbackVerdict.BAD, FeedbackStatus.NEW));

        assertThrows(BizException.class, () -> service.convert(FEEDBACK_ID, DATASET_ID));

        verify(evalDatasetService, never()).collectFromRetrieval(anyString(), anyString(), any(),
                anyList(), any(), any());
    }

    @Test
    void shouldRefuseToConvertWhenTheChunkNoLongerExists() {
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(feedback(FeedbackVerdict.GOOD, FeedbackStatus.NEW));
        when(chunkMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.convert(FEEDBACK_ID, DATASET_ID));

        verify(evalDatasetService, never()).collectFromRetrieval(anyString(), anyString(), any(),
                anyList(), any(), any());
    }

    @Test
    void shouldDismissANewFeedback() {
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(feedback(FeedbackVerdict.BAD, FeedbackStatus.NEW));

        RetrievalFeedback dismissed = service.dismiss(FEEDBACK_ID);

        verify(retrievalFeedbackMapper).updateById(any(RetrievalFeedback.class));
        assertEquals(FeedbackStatus.DISMISSED, dismissed.getStatus());
    }

    @Test
    void shouldTreatBothNonNewStatesAsTerminal() {
        when(retrievalFeedbackMapper.selectOne(any()))
                .thenReturn(feedback(FeedbackVerdict.GOOD, FeedbackStatus.CONVERTED));
        assertThrows(BizException.class, () -> service.convert(FEEDBACK_ID, DATASET_ID));
        assertThrows(BizException.class, () -> service.dismiss(FEEDBACK_ID));

        when(retrievalFeedbackMapper.selectOne(any()))
                .thenReturn(feedback(FeedbackVerdict.GOOD, FeedbackStatus.DISMISSED));
        assertThrows(BizException.class, () -> service.convert(FEEDBACK_ID, DATASET_ID));
        assertThrows(BizException.class, () -> service.dismiss(FEEDBACK_ID));
    }

    @Test
    void shouldFailLoudlyWhenTheFeedbackIsUnknown() {
        when(retrievalFeedbackMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.dismiss(FEEDBACK_ID));
    }

    private RetrievalFeedback feedback(FeedbackVerdict verdict, FeedbackStatus status) {
        RetrievalFeedback feedback = new RetrievalFeedback();
        feedback.setFeedbackId(FEEDBACK_ID);
        feedback.setKbId(KB_ID);
        feedback.setQuery(QUERY);
        feedback.setChunkId(CHUNK_ID);
        feedback.setVerdict(verdict);
        feedback.setStatus(status);
        return feedback;
    }

    private Chunk chunk() {
        Chunk chunk = new Chunk();
        chunk.setChunkId(CHUNK_ID);
        chunk.setDocId(DOC_ID);
        return chunk;
    }
}
