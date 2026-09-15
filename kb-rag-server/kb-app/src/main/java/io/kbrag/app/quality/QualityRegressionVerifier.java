package io.kbrag.app.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.eval.CorpusFingerprintFactory;
import io.kbrag.app.eval.EvalRunService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.KnowledgeQualityIssue;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.RunStatus;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.model.AnswerEvaluationConfig;
import io.kbrag.domain.model.EvalCaseInput;
import io.kbrag.domain.model.EvalEvidence;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 核验一条纠正用例的实际回归证据，不以批次完成或平均分替代该用例的结果。 */
@Component
@RequiredArgsConstructor
public class QualityRegressionVerifier {
    /** 五个维度分别达标，避免平均分掩盖错误答案或错误引用。 */
    public static final int MINIMUM_DIMENSION_SCORE = 4;
    private static final int MAXIMUM_DIMENSION_SCORE = 5;

    private final EvalRunService runs;
    private final EvalResultMapper results;
    private final CorpusFingerprintFactory fingerprints;
    private final AppVersionService versions;
    private final QualityIssueAccess access;

    /** 返回经过当前证据、配置和用例输入核验的版本与结果，调用方仍需人工确认说明。 */
    public Verification verify(KnowledgeQualityIssue issue, String runId) {
        var run = runs.requireRun(runId);
        if (!Objects.equals(issue.getKbId(), run.getKbId())
                || !Objects.equals(issue.getDatasetId(), run.getDatasetId())) {
            throw BizException.notFound("quality regression run not found");
        }
        if (run.getStatus() != RunStatus.SUCCESS || StringUtils.isBlank(run.getCaseInputs())
                || StringUtils.isBlank(run.getAnswerEvalConfig())) {
            throw BizException.invalidParam("请选择已成功完成且保留用例输入的答案评测，纯检索或旧评测不能用于解决问题");
        }
        EvalCaseInput expected = JsonUtil.parse(issue.getExpectedCaseInput(), EvalCaseInput.class);
        var currentCase = access.lockCase(issue);
        if (currentCase.getStatus() != CaseStatus.ACTIVE
                || !sameInput(expected, EvalCaseInput.capture(currentCase))) {
            throw BizException.invalidParam("纠正用例已变更或证据待复核，请重新保存纠正内容并回归");
        }
        List<EvalCaseInput> inputs = JsonUtil.parse(run.getCaseInputs(), new TypeReference<List<EvalCaseInput>>() { });
        List<EvalCaseInput> matched = inputs.stream().filter(input -> Objects.equals(input.caseId(), issue.getCaseId())).toList();
        if (matched.size() != 1 || !sameInput(expected, matched.get(0))) {
            throw BizException.invalidParam("此次评测没有执行当前纠正内容，请提交新的答案评测");
        }
        AnswerEvaluationConfig config = JsonUtil.parse(run.getAnswerEvalConfig(), AnswerEvaluationConfig.class);
        var verifiedVersion = access.requireVersion(issue.getKbId(), config.appVersionId());
        var affectedVersion = access.requireVersion(issue.getKbId(), issue.getAffectedAppVersionId());
        if (!Objects.equals(verifiedVersion.getAppId(), affectedVersion.getAppId())) {
            throw BizException.invalidParam("回归版本必须属于受影响的同一应用");
        }
        if (!sameJson(JsonUtil.toJson(config.snapshot()), JsonUtil.toJson(versions.parseConfig(verifiedVersion)))) {
            throw BizException.invalidParam("回归后应用配置已修改，请重新执行答案评测");
        }
        if (!Objects.equals(run.getCorpusFingerprint(), fingerprints.fingerprint(issue.getKbId()))) {
            throw BizException.invalidParam("回归后知识库或检索依赖已变化，请重新执行答案评测");
        }
        List<EvalEvidence> evidences = JsonUtil.parse(expected.evidences(), new TypeReference<List<EvalEvidence>>() { });
        for (EvalEvidence evidence : evidences) {
            var document = access.requireDocument(issue.getKbId(), evidence.getDocId());
            if (!document.availableForRetrievalAt(LocalDateTime.now()) || document.getProcessStatus() != ProcessStatus.INDEXED
                    || !Objects.equals(document.getCurrentVersionId(), evidence.getAnnotatedVersionId())) {
                throw BizException.invalidParam("纠正证据版本已变化或当前不可检索，请复核证据后重新评测");
            }
        }
        EvalResult result = results.selectOne(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getRunId, runId).eq(EvalResult::getCaseId, issue.getCaseId()));
        Set<String> protectedDocIds = result == null ? Set.of() : access.requireResult(issue.getKbId(), result);
        requirePassingResult(expected, result);
        return new Verification(config.appVersionId(), result, protectedDocIds);
    }

    private void requirePassingResult(EvalCaseInput expected, EvalResult result) {
        if (result == null || !Boolean.TRUE.equals(result.getAnswerJudgeRequested())
                || StringUtils.isBlank(result.getGeneratedAnswer()) || !Boolean.TRUE.equals(result.getRefusalCorrect())) {
            throw BizException.invalidParam("该用例缺少有效答案判断，或回答与拒答行为不符合标准");
        }
        if (StringUtils.isNotBlank(result.getDegraded())
                && !JsonUtil.parse(result.getDegraded(), new TypeReference<List<String>>() { }).isEmpty()) {
            throw BizException.invalidParam("该用例存在降级，不能作为解决问题的回归依据");
        }
        for (Integer score : new Integer[]{result.getAnswerCorrectness(), result.getAnswerFaithfulness(),
                result.getAnswerCompleteness(), result.getCitationCorrectness(), result.getCitationCompleteness()}) {
            if (score == null || score < MINIMUM_DIMENSION_SCORE || score > MAXIMUM_DIMENSION_SCORE) {
                throw BizException.invalidParam("正确性、忠实性、完整性和两项引用评分均须至少达到 4/5");
            }
        }
        if (!Boolean.TRUE.equals(expected.expectedRefusal()) && (!Integer.valueOf(1).equals(result.getHit())
                || result.getEvidenceTotalCount() == null || result.getEvidenceTotalCount() < 1
                || !Objects.equals(result.getEvidenceHitCount(), result.getEvidenceTotalCount()))) {
            throw BizException.invalidParam("正常回答的标准证据尚未全部召回，不能解决此问题");
        }
    }

    /** JSON 列在 MySQL 中会改变空白和对象键顺序，输入比较使用语义值。 */
    static boolean sameInput(EvalCaseInput left, EvalCaseInput right) {
        return Objects.equals(left.caseId(), right.caseId()) && Objects.equals(left.datasetId(), right.datasetId())
                && Objects.equals(left.query(), right.query()) && sameJson(left.messages(), right.messages())
                && Objects.equals(left.expectedAnswer(), right.expectedAnswer())
                && Boolean.TRUE.equals(left.expectedRefusal()) == Boolean.TRUE.equals(right.expectedRefusal())
                && left.anchorType() == right.anchorType() && left.status() == right.status()
                && sameJson(left.evidences(), right.evidences());
    }

    private static boolean sameJson(String left, String right) {
        return Objects.equals(left == null ? null : JsonUtil.parse(left, Object.class),
                right == null ? null : JsonUtil.parse(right, Object.class));
    }

    /** 只在授权后的详情投影中使用，不直接暴露实体序列化。 */
    public record Verification(String appVersionId, EvalResult result, Set<String> protectedDocIds) { }
}
