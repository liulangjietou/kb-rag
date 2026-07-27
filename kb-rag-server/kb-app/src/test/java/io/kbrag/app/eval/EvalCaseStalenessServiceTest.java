package io.kbrag.app.eval;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.OverlapRatioCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two placeholders M4a left behind (M4b contract section 0): the pre-flight
 * {@code affected_eval_case_count} and the actual {@code evidence_stale} marking a document version
 * switch triggers, requirement section 4.5.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalCaseStalenessServiceTest {

    private static final String KB_ID = "kb_test";
    private static final String DOC_ID = "doc_a";
    private static final String OLD_VERSION_ID = "dv_old";
    private static final String NEW_VERSION_ID = "dv_new";

    private EvalCaseMapper evalCaseMapper;
    private DocumentMapper documentMapper;
    private ChunkMapper chunkMapper;
    private EvalCaseStalenessService service;

    @BeforeEach
    void setUp() {
        evalCaseMapper = mock(EvalCaseMapper.class);
        EvalDatasetMapper evalDatasetMapper = mock(EvalDatasetMapper.class);
        documentMapper = mock(DocumentMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        KbProperties properties = new KbProperties();

        service = new EvalCaseStalenessService(evalCaseMapper, evalDatasetMapper, documentMapper, chunkMapper,
                new OverlapRatioCalculator(new ChunkTextHasher()), properties);

        when(documentMapper.selectOne(any())).thenReturn(document());
        when(evalDatasetMapper.selectList(any())).thenReturn(List.of(dataset()));
    }

    @Test
    void shouldCountAndMarkACaseWhoseEvidenceNoLongerMatchesTheTargetVersion() {
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(spanCase("evc_stale", "the exact quoted span")));
        // The new version's chunks say something else entirely - the span cannot be found in it.
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("unrelated content about a different topic")));

        assertEquals(1, service.staleCount(DOC_ID, NEW_VERSION_ID));

        List<String> marked = service.markStale(DOC_ID, NEW_VERSION_ID);
        assertEquals(List.of("evc_stale"), marked);
    }

    @Test
    void shouldNotCountOrMarkACaseWhoseEvidenceStillMatchesTheTargetVersion() {
        String span = "the exact quoted span";
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(spanCase("evc_fresh", span)));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("context before " + span + " context after")));

        assertEquals(0, service.staleCount(DOC_ID, NEW_VERSION_ID));
        assertTrue(service.markStale(DOC_ID, NEW_VERSION_ID).isEmpty());
    }

    @Test
    void shouldCountOnlyTheEvidenceThatActuallyReferencesTheChangingDocument() {
        // A case anchored to a different document entirely must never be pulled in just because some
        // other case in the same data set happens to reference the document that is switching.
        EvalCase unrelated = spanCase("evc_unrelated", "some other span");
        unrelated.setEvidences(JsonUtil.toJson(List.of(evidenceFor("doc_other", "some other span"))));
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(unrelated));

        assertEquals(0, service.staleCount(DOC_ID, NEW_VERSION_ID));
    }

    private EvalDataset dataset() {
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId("evds_1");
        dataset.setKbId(KB_ID);
        return dataset;
    }

    private EvalCase spanCase(String caseId, String span) {
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(caseId);
        evalCase.setDatasetId("evds_1");
        evalCase.setAnchorType(AnchorType.SPAN);
        evalCase.setStatus(CaseStatus.ACTIVE);
        evalCase.setEvidences(JsonUtil.toJson(List.of(evidenceFor(DOC_ID, span))));
        return evalCase;
    }

    private EvalEvidence evidenceFor(String docId, String span) {
        EvalEvidence evidence = new EvalEvidence();
        evidence.setDocId(docId);
        evidence.setSpan(span);
        return evidence;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId(DOC_ID);
        document.setKbId(KB_ID);
        document.setCurrentVersionId(OLD_VERSION_ID);
        return document;
    }

    private Chunk chunk(String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId("ck_1");
        chunk.setDocId(DOC_ID);
        chunk.setDocumentVersionId(NEW_VERSION_ID);
        chunk.setContent(content);
        return chunk;
    }
}
