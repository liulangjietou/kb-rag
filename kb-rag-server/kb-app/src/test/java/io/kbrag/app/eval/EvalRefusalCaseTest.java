package io.kbrag.app.eval;

import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.mapper.*;
import io.kbrag.domain.service.BizIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 无依据拒答不应被迫把错误资料标记成正确证据。 */
class EvalRefusalCaseTest {
    @Test
    void refusalWithoutEvidenceIsARealCaseButNormalAnswerStillRequiresEvidence() {
        EvalDatasetMapper datasets = mock(EvalDatasetMapper.class);
        EvalCaseMapper cases = mock(EvalCaseMapper.class);
        DocumentMapper documents = mock(DocumentMapper.class);
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId("evds_refusal"); dataset.setDatasetRevision(0); dataset.setCaseCount(0);
        when(datasets.selectOne(any())).thenReturn(dataset);
        when(datasets.updateById(any(EvalDataset.class))).thenReturn(1);
        BizIdGenerator ids = mock(BizIdGenerator.class);
        when(ids.evalCaseId()).thenReturn("evc_refusal");
        EvalDatasetService service = new EvalDatasetService(datasets, cases, mock(EvalRunMapper.class),
                mock(EvalResultMapper.class), documents, mock(ChunkMapper.class), mock(KnowledgeBaseService.class), ids);
        var saved = service.createCase("evds_refusal", EvalCaseCommand.builder().query("资料中没有的内容")
                .anchorType(AnchorType.DOCUMENT).expectedRefusal(true).evidences(List.of()).build());
        assertTrue(saved.getExpectedRefusal());
        assertEquals("[]", saved.getEvidences());
        assertEquals(1, dataset.getCaseCount());
        verifyNoInteractions(documents);
        assertThrows(BizException.class, () -> service.createCase("evds_refusal", EvalCaseCommand.builder()
                .query("需要有依据的正常回答").anchorType(AnchorType.DOCUMENT).evidences(List.of()).build()));
    }
}
