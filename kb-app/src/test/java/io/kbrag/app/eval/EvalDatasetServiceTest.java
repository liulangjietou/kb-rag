package io.kbrag.app.eval;

import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code dataset_revision} and {@code case_count} maintenance of requirement section 4.6: every
 * case insert, edit, delete and status change must bump the revision inside the same mutation, which is
 * the compare endpoint's whole safety net.
 *
 * @author owlzhangfq@gmail.com
 */
class EvalDatasetServiceTest {

    private static final String DATASET_ID = "evds_test";
    private static final String KB_ID = "kb_test";

    private EvalDatasetMapper evalDatasetMapper;
    private EvalCaseMapper evalCaseMapper;
    private DocumentMapper documentMapper;
    private BizIdGenerator bizIdGenerator;
    private EvalDatasetService service;

    @BeforeEach
    void setUp() {
        evalDatasetMapper = mock(EvalDatasetMapper.class);
        evalCaseMapper = mock(EvalCaseMapper.class);
        EvalRunMapper evalRunMapper = mock(EvalRunMapper.class);
        EvalResultMapper evalResultMapper = mock(EvalResultMapper.class);
        documentMapper = mock(DocumentMapper.class);
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        io.kbrag.app.kb.KnowledgeBaseService knowledgeBaseService = mock(io.kbrag.app.kb.KnowledgeBaseService.class);
        bizIdGenerator = mock(BizIdGenerator.class);

        service = new EvalDatasetService(evalDatasetMapper, evalCaseMapper, evalRunMapper, evalResultMapper,
                documentMapper, chunkMapper, knowledgeBaseService, bizIdGenerator);

        when(bizIdGenerator.evalCaseId()).thenReturn("evc_new");
        when(documentMapper.selectOne(any())).thenReturn(document());
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(0, 0));
    }

    @Test
    void creatingACaseShouldIncrementBothTheRevisionAndTheCaseCount() {
        service.createCase(DATASET_ID, command());

        EvalDataset saved = captureSavedDataset();
        assertEquals(1, saved.getDatasetRevision());
        assertEquals(1, saved.getCaseCount());
    }

    @Test
    void updatingAnActiveCaseShouldBumpTheRevisionButNotTheCaseCount() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(3, 2));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.ACTIVE));

        service.updateCase("evc_1", command());

        EvalDataset saved = captureSavedDataset();
        assertEquals(4, saved.getDatasetRevision());
        assertEquals(2, saved.getCaseCount());
    }

    @Test
    void reactivatingADeprecatedCaseThroughUpdateShouldRestoreItsCaseCount() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(3, 2));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.DEPRECATED));

        service.updateCase("evc_1", command());

        EvalDataset saved = captureSavedDataset();
        assertEquals(4, saved.getDatasetRevision());
        assertEquals(3, saved.getCaseCount());
    }

    @Test
    void deletingAnActiveCaseShouldBumpTheRevisionAndDecrementTheCaseCount() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(5, 4));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.ACTIVE));

        service.deleteCase("evc_1");

        EvalDataset saved = captureSavedDataset();
        assertEquals(6, saved.getDatasetRevision());
        assertEquals(3, saved.getCaseCount());
    }

    @Test
    void deletingAnAlreadyDeprecatedCaseShouldNotDecrementTheCaseCountTwice() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(5, 4));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.DEPRECATED));

        service.deleteCase("evc_1");

        EvalDataset saved = captureSavedDataset();
        assertEquals(6, saved.getDatasetRevision());
        assertEquals(4, saved.getCaseCount());
    }

    @Test
    void deprecatingAnActiveCaseThroughRecheckShouldDecrementTheCaseCount() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(2, 5));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.ACTIVE));

        service.recheck("evc_1", EvalRecheckAction.DEPRECATE, null);

        EvalDataset saved = captureSavedDataset();
        assertEquals(3, saved.getDatasetRevision());
        assertEquals(4, saved.getCaseCount());
    }

    @Test
    void reanchoringAStaleCaseShouldBumpTheRevisionWithoutChangingTheCaseCount() {
        when(evalDatasetMapper.selectOne(any())).thenReturn(dataset(2, 5));
        when(evalCaseMapper.selectOne(any())).thenReturn(existingCase(CaseStatus.EVIDENCE_STALE));

        service.recheck("evc_1", EvalRecheckAction.REANCHOR, List.of(evidence()));

        EvalDataset saved = captureSavedDataset();
        assertEquals(3, saved.getDatasetRevision());
        assertEquals(5, saved.getCaseCount());
    }

    private EvalDataset captureSavedDataset() {
        ArgumentCaptor<EvalDataset> captor = ArgumentCaptor.forClass(EvalDataset.class);
        verify(evalDatasetMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private EvalDataset dataset(int revision, int caseCount) {
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId(DATASET_ID);
        dataset.setKbId(KB_ID);
        dataset.setDatasetRevision(revision);
        dataset.setCaseCount(caseCount);
        return dataset;
    }

    private EvalCase existingCase(CaseStatus status) {
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId("evc_1");
        evalCase.setDatasetId(DATASET_ID);
        evalCase.setAnchorType(AnchorType.SPAN);
        evalCase.setStatus(status);
        return evalCase;
    }

    private Document document() {
        Document document = new Document();
        document.setDocId("doc_1");
        document.setKbId(KB_ID);
        document.setCurrentVersionId("dv_1");
        return document;
    }

    private EvalEvidence evidence() {
        EvalEvidence evidence = new EvalEvidence();
        evidence.setDocId("doc_1");
        evidence.setSpan("some span text");
        return evidence;
    }

    private EvalCaseCommand command() {
        return EvalCaseCommand.builder()
                .query("why does it matter")
                .anchorType(AnchorType.SPAN)
                .evidences(List.of(evidence()))
                .build();
    }
}
