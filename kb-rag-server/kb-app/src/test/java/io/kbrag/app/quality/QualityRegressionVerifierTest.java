package io.kbrag.app.quality;

import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.eval.CorpusFingerprintFactory;
import io.kbrag.app.eval.EvalRunService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.model.AnswerEvaluationConfig;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.EvalCaseInput;
import io.kbrag.domain.model.KbRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 解决条件针对实际单例结果、冻结输入和当前可读证据，不依赖汇总平均分。 */
class QualityRegressionVerifierTest {
    private final EvalRunService runs = mock(EvalRunService.class);
    private final EvalResultMapper results = mock(EvalResultMapper.class);
    private final CorpusFingerprintFactory fingerprints = mock(CorpusFingerprintFactory.class);
    private final AppVersionService versions = mock(AppVersionService.class);
    private final QualityIssueAccess access = mock(QualityIssueAccess.class);
    private final QualityRegressionVerifier verifier = new QualityRegressionVerifier(runs, results, fingerprints, versions, access);
    private final KnowledgeQualityIssue issue = new KnowledgeQualityIssue();
    private final EvalRun run = new EvalRun();
    private final EvalResult result = new EvalResult();
    private final Document document = new Document();
    private final AppConfigSnapshot config = new AppConfigSnapshot();
    private EvalCaseInput input;

    @BeforeEach
    void setUp() {
        input = input("[{\"doc_id\":\"doc_safe\",\"annotated_version_id\":\"dv_safe\"}]", false);
        issue.setKbId("kb_safe"); issue.setDatasetId("ds_safe"); issue.setCaseId("case_safe");
        issue.setExpectedCaseInput(JsonUtil.toJson(input)); issue.setAffectedAppVersionId("av_old");
        run.setKbId("kb_safe"); run.setDatasetId("ds_safe"); run.setStatus(RunStatus.SUCCESS);
        run.setCaseInputs(JsonUtil.toJson(List.of(input))); run.setCorpusFingerprint("fp_safe");
        config.setKbRefs(List.of(KbRef.of("kb_safe"))); config.setChatModel("model_safe");
        run.setAnswerEvalConfig(JsonUtil.toJson(new AnswerEvaluationConfig("av_new", config)));
        AppVersion version = new AppVersion(); version.setAppId("app_safe");
        when(runs.requireRun("run_safe")).thenReturn(run);
        when(access.lockCase(issue)).thenReturn(input.toCase());
        when(access.requireVersion("kb_safe", "av_old")).thenReturn(version);
        when(access.requireVersion("kb_safe", "av_new")).thenReturn(version);
        when(versions.parseConfig(version)).thenReturn(config);
        when(fingerprints.fingerprint("kb_safe")).thenReturn("fp_safe");
        document.setCurrentVersionId("dv_safe"); document.setProcessStatus(ProcessStatus.INDEXED);
        when(access.requireDocument("kb_safe", "doc_safe")).thenReturn(document);
        when(results.selectOne(any())).thenReturn(result);
        when(access.requireResult("kb_safe", result)).thenReturn(Set.of("doc_safe", "doc_extra"));
        result.setGeneratedAnswer("标准回答 [1]"); result.setAnswerJudgeRequested(true); result.setRefusalCorrect(true);
        result.setAnswerCorrectness(4); result.setAnswerFaithfulness(4); result.setAnswerCompleteness(4);
        result.setCitationCorrectness(4); result.setCitationCompleteness(4);
        result.setHit(1); result.setEvidenceHitCount(1); result.setEvidenceTotalCount(1);
    }

    @Test
    void acceptsOnlyTheCurrentConfirmedInputAndProtectsAdditionalRecalledDocuments() {
        var verified = verifier.verify(issue, "run_safe");
        assertEquals("av_new", verified.appVersionId());
        assertEquals(Set.of("doc_safe", "doc_extra"), verified.protectedDocIds());
    }

    @Test
    void mysqlJsonKeyOrderAndWhitespaceDoNotInvalidateTheSameEvidence() {
        run.setCaseInputs(JsonUtil.toJson(List.of(input("[ { \"annotated_version_id\": \"dv_safe\", \"doc_id\": \"doc_safe\" } ]", false))));
        assertEquals("av_new", verifier.verify(issue, "run_safe").appVersionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_kb", "wrong_dataset", "pending", "legacy", "retrieval_only", "missing_case", "changed_input",
            "changed_current_case", "changed_config", "changed_corpus", "stale_document", "not_indexed", "trashed", "expired",
            "missing_result", "judge_missing", "answer_missing", "refusal_wrong", "degraded", "low_correctness", "low_citation",
            "missing_dimension", "missed_evidence", "other_app", "denied_recalled_evidence"})
    void rejectsIncompleteOrObsoleteProof(String reason) {
        switch (reason) {
            case "wrong_kb" -> run.setKbId("kb_other");
            case "wrong_dataset" -> run.setDatasetId("ds_other");
            case "pending" -> run.setStatus(RunStatus.PENDING);
            case "legacy" -> run.setCaseInputs(null);
            case "retrieval_only" -> run.setAnswerEvalConfig(null);
            case "missing_case" -> run.setCaseInputs("[]");
            case "changed_input" -> run.setCaseInputs(JsonUtil.toJson(List.of(input("[]", false))));
            case "changed_current_case" -> { var changed = input.toCase(); changed.setQuery("外部修改"); when(access.lockCase(issue)).thenReturn(changed); }
            case "changed_config" -> config.setChatModel("changed_model");
            case "changed_corpus" -> when(fingerprints.fingerprint("kb_safe")).thenReturn("fp_changed");
            case "stale_document" -> document.setCurrentVersionId("dv_changed");
            case "not_indexed" -> document.setProcessStatus(ProcessStatus.INDEXING);
            case "trashed" -> document.setTrashed(1);
            case "expired" -> document.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
            case "missing_result" -> when(results.selectOne(any())).thenReturn(null);
            case "judge_missing" -> result.setAnswerJudgeRequested(false);
            case "answer_missing" -> result.setGeneratedAnswer(" ");
            case "refusal_wrong" -> result.setRefusalCorrect(false);
            case "degraded" -> result.setDegraded("[\"EMBEDDING_UNAVAILABLE\"]");
            case "low_correctness" -> result.setAnswerCorrectness(3);
            case "low_citation" -> result.setCitationCorrectness(3);
            case "missing_dimension" -> result.setAnswerFaithfulness(null);
            case "missed_evidence" -> result.setEvidenceHitCount(0);
            case "other_app" -> { var other = new AppVersion(); other.setAppId("app_other"); when(access.requireVersion("kb_safe", "av_old")).thenReturn(other); }
            case "denied_recalled_evidence" -> when(access.requireResult("kb_safe", result)).thenThrow(BizException.forbidden("restricted"));
            default -> throw new AssertionError(reason);
        }
        assertThrows(BizException.class, () -> verifier.verify(issue, "run_safe"));
    }

    @Test
    void validRefusalDoesNotRequireFabricatedRecallEvidence() {
        EvalCaseInput refusal = input("[]", true);
        issue.setExpectedCaseInput(JsonUtil.toJson(refusal)); run.setCaseInputs(JsonUtil.toJson(List.of(refusal)));
        when(access.lockCase(issue)).thenReturn(refusal.toCase());
        result.setHit(0); result.setEvidenceHitCount(0); result.setEvidenceTotalCount(0);
        assertEquals("av_new", verifier.verify(issue, "run_safe").appVersionId());
    }

    private EvalCaseInput input(String evidence, boolean refusal) {
        return new EvalCaseInput("case_safe", "ds_safe", "如何办理", null, refusal ? null : "标准答案", refusal,
                AnchorType.DOCUMENT, evidence, CaseStatus.ACTIVE);
    }
}
