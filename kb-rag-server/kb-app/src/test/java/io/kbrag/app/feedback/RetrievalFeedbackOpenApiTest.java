package io.kbrag.app.feedback;

import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.app.openapi.ApiKeyPrincipal;
import io.kbrag.app.support.MybatisLambdaCache;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.FeedbackChannel;
import io.kbrag.domain.enums.FeedbackStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the open API half of the feedback loop, the M16 contract section 7: the caller names the
 * {@code request_id} of the retrieval it saw, never a knowledge base, and everything else - the
 * knowledge base, the masked query digest - is looked up from the insight row that id points at. A
 * verdict that cannot be tied to a recorded retrieval is refused outright, and the free form
 * comment is cut to the note column width rather than rejected.
 *
 * <p>The request id is a correlation id, not a credential: it arrives in a header the caller controls,
 * so the authorisation is the application recorded on the insight row being inside this key's scope.
 * The two refusal cases below - another application's retrieval, another knowledge base's chunk - are
 * what keeps a key from writing verdicts against a corpus it cannot read.
 *
 * @author owlzhangfq@gmail.com
 */
class RetrievalFeedbackOpenApiTest {

    private static final String FEEDBACK_ID = "rfb_1";
    private static final String REQUEST_ID = "req_1";
    private static final String KB_ID = "kb_1";
    private static final String OTHER_KB_ID = "kb_2";
    private static final String QUERY_DIGEST = "如何重置**";
    private static final String CHUNK_ID = "ck_1";
    private static final String DOC_ID = "doc_1";
    private static final String APP_ID = "app_1";
    private static final String OTHER_APP_ID = "app_2";
    private static final String END_USER_ID = "enduser-7";
    private static final String KEY_ID = "key_1";
    private static final int QPS_LIMIT = 10;
    private static final int NOTE_MAX_LENGTH = 512;

    private RetrievalFeedbackMapper retrievalFeedbackMapper;
    private SearchInsightMapper searchInsightMapper;
    private ChunkMapper chunkMapper;
    private RetrievalFeedbackService service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.register(RetrievalFeedback.class, SearchInsight.class, Chunk.class);
        retrievalFeedbackMapper = mock(RetrievalFeedbackMapper.class);
        searchInsightMapper = mock(SearchInsightMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        BizIdGenerator bizIdGenerator = mock(BizIdGenerator.class);
        when(bizIdGenerator.retrievalFeedbackId()).thenReturn(FEEDBACK_ID);
        service = new RetrievalFeedbackService(retrievalFeedbackMapper, chunkMapper,
                searchInsightMapper, mock(EvalDatasetService.class),
                mock(KnowledgeBaseService.class), bizIdGenerator);
    }

    @Test
    void shouldResolveTheKnowledgeBaseAndQueryFromTheRecordedRetrieval() {
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());
        when(chunkMapper.selectOne(any())).thenReturn(chunk(KB_ID));

        RetrievalFeedback stored = service.recordFromOpenApi(principal(APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.GOOD, null, END_USER_ID);

        verify(retrievalFeedbackMapper).insert(any(RetrievalFeedback.class));
        assertEquals(KB_ID, stored.getKbId());
        // The digest, not the raw text: the open channel must not bypass the insight masking.
        assertEquals(QUERY_DIGEST, stored.getQuery());
        assertEquals(FeedbackChannel.OPEN_API, stored.getChannel());
        assertEquals(END_USER_ID, stored.getEndUserId());
        assertEquals(FeedbackStatus.NEW, stored.getStatus());
        assertEquals(DOC_ID, stored.getDocId());
    }

    @Test
    void shouldRefuseAVerdictNamingNoRecordedRetrieval() {
        // Unknown or already purged by the insight retention window: an anonymous verdict can
        // never be converted into evaluation material, which makes it noise, not data.
        when(searchInsightMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.recordFromOpenApi(principal(APP_ID),
                "req_unknown", CHUNK_ID, FeedbackVerdict.GOOD, null, null));

        verify(retrievalFeedbackMapper, never()).insert(any(RetrievalFeedback.class));
    }

    @Test
    void shouldRefuseAVerdictOnAnotherApplicationsRetrieval() {
        // The request id travels in a header the caller picks, so it authorises nothing on its own:
        // the key's scope has to cover the application that actually ran the retrieval.
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());

        assertThrows(BizException.class, () -> service.recordFromOpenApi(principal(OTHER_APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.GOOD, null, null));

        verify(retrievalFeedbackMapper, never()).insert(any(RetrievalFeedback.class));
    }

    @Test
    void shouldRefuseAChunkOfAnotherKnowledgeBase() {
        // Nothing legitimate produces this: the named retrieval only ever returned chunks of its own
        // base, so a foreign chunk id is an attempt to attach evidence from a corpus never served.
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());
        when(chunkMapper.selectOne(any())).thenReturn(chunk(OTHER_KB_ID));

        assertThrows(BizException.class, () -> service.recordFromOpenApi(principal(APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.GOOD, null, null));

        verify(retrievalFeedbackMapper, never()).insert(any(RetrievalFeedback.class));
    }

    @Test
    void shouldCutTheCommentToTheNoteColumnWidthInsteadOfRejectingIt() {
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());
        when(chunkMapper.selectOne(any())).thenReturn(chunk(KB_ID));
        String comment = "很".repeat(NOTE_MAX_LENGTH + 100);

        RetrievalFeedback stored = service.recordFromOpenApi(principal(APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.BAD, comment, null);

        assertEquals(NOTE_MAX_LENGTH, stored.getNote().length());
    }

    @Test
    void shouldStoreNoNoteForABlankComment() {
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());
        when(chunkMapper.selectOne(any())).thenReturn(chunk(KB_ID));

        RetrievalFeedback stored = service.recordFromOpenApi(principal(APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.BAD, "   ", null);

        assertNull(stored.getNote());
    }

    @Test
    void shouldRecordAVerdictWhoseChunkIsAlreadyGone() {
        // Same tolerance as the console path: the retrieval happened, the signal stands, only the
        // document attribution is lost with the chunk. A deletion is not an authorisation problem.
        when(searchInsightMapper.selectOne(any())).thenReturn(insight());
        when(chunkMapper.selectOne(any())).thenReturn(null);

        RetrievalFeedback stored = service.recordFromOpenApi(principal(APP_ID),
                REQUEST_ID, CHUNK_ID, FeedbackVerdict.GOOD, null, null);

        verify(retrievalFeedbackMapper).insert(any(RetrievalFeedback.class));
        assertNull(stored.getDocId());
    }

    private SearchInsight insight() {
        SearchInsight insight = new SearchInsight();
        insight.setRequestId(REQUEST_ID);
        insight.setKbId(KB_ID);
        insight.setQueryDigest(QUERY_DIGEST);
        insight.setAppId(APP_ID);
        return insight;
    }

    private Chunk chunk(String kbId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(CHUNK_ID);
        chunk.setKbId(kbId);
        chunk.setDocId(DOC_ID);
        return chunk;
    }

    private ApiKeyPrincipal principal(String appId) {
        return new ApiKeyPrincipal(KEY_ID, "test key", QPS_LIMIT, List.of(appId));
    }
}
