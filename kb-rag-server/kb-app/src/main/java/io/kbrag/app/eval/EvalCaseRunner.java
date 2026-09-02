package io.kbrag.app.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.app.chat.AnswerGenerationService;
import io.kbrag.app.retrieval.OfflineExecutionContext;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalMetadataKeys;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.EvalMode;
import io.kbrag.domain.model.AnswerEvaluationConfig;
import io.kbrag.domain.model.CaseJudgment;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.model.EvalRetrievalConfig;
import io.kbrag.domain.service.EvalHitJudge;
import io.kbrag.domain.service.OverlapRatioCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs one evaluation case end to end and reports what happened to it.
 *
 * <p>Split out of {@link EvalRunService} along the boundary between a run and a case. The service owns
 * the run: it validates a submission, creates one row per configuration of the matrix, fans the cases out
 * over the case executor, aggregates the metrics and writes the results. This class owns a single case -
 * search, retry a degraded answer, judge the hit, optionally ask the LLM judge and optionally generate and
 * judge a final answer - and knows nothing about runs, matrices or persistence.
 *
 * <p>Nothing here touches a mapper. A case is a pure function of the corpus and the configuration under
 * test, which is what makes it safe to run many of them concurrently; the moment it wrote a row it would
 * stop being one.
 *
 * <p>{@link #answerJudgeRequested} is static and public because the cost estimate has to predict the very
 * same decision before any case runs. Two copies of that predicate would be two answers to "will this case
 * call the model", and the estimate exists precisely to be trusted.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCaseRunner {

    private final RetrievalService retrievalService;
    private final EvalJudgeService evalJudgeService;
    private final AnswerGenerationService answerGenerationService;
    private final FinalAnswerJudgeService finalAnswerJudgeService;
    private final EvalHitJudge evalHitJudge;
    private final OverlapRatioCalculator overlapRatioCalculator;
    private final KbProperties properties;

    /**
     * What one case produced.
     *
     * @param caseId           case business id
     * @param judgment         case level judgment
     * @param overlapRatios    best individual overlap ratio per evidence, empty for a document anchor
     * @param recalledChunkIds chunk ids the top K returned
     * @param degraded         degradation markers observed on the final attempt
     * @param retryCount       automatic retries actually performed
     * @param judgeOutcome     LLM-as-judge outcome, {@code null} when not judged
     * @param answerOutcome    generated answer and its judgment, {@code null} when not requested
     * @param anchorType       anchoring granularity, for metrics grouping
     * @param multiTurn        {@code true} when the case carries a conversation history
     */
    public record CaseOutcome(String caseId, CaseJudgment judgment, List<Double> overlapRatios,
                              List<String> recalledChunkIds, List<String> degraded, int retryCount,
                              EvalJudgeService.JudgeOutcome judgeOutcome, AnswerCaseOutcome answerOutcome,
                              AnchorType anchorType,
                              boolean multiTurn) {
    }

    /** Generated answer and its structured judgment for one judgeable case. */
    public record AnswerCaseOutcome(String generatedAnswer, int generationLatencyMs,
                                    FinalAnswerJudgeService.JudgeOutcome judgeOutcome) {
    }

    /**
     * Tells whether a case asks for the final answer stage.
     *
     * <p>A refusal expectation counts: "this question must be declined" is judged on a generated answer
     * exactly like a factual one, and skipping generation for it would make the refusal untestable.
     *
     * @param evalCase case being inspected
     * @return {@code true} when the case is to be answered and judged
     */
    public static boolean answerJudgeRequested(EvalCase evalCase) {
        return Boolean.TRUE.equals(evalCase.getExpectedRefusal())
                || evalCase.getExpectedAnswer() != null && !evalCase.getExpectedAnswer().isBlank();
    }

    /**
     * Searches for one case and judges what came back.
     *
     * <p>A degraded answer is retried up to the configured budget: a degradation means a route the
     * configuration asked for did not run, so the numbers measured on it would describe a different
     * configuration than the one under test.
     *
     * @param kbId         knowledge base business id
     * @param evalCase     case to run
     * @param config       retrieval configuration under test
     * @param judgeEnabled {@code true} runs the LLM-as-judge stage
     * @param answerConfig final answer configuration, {@code null} when the run does not answer
     * @return the case outcome
     */
    public CaseOutcome run(String kbId, EvalCase evalCase, EvalRetrievalConfig config,
                           boolean judgeEnabled, AnswerEvaluationConfig answerConfig) {
        RetrievalCommand command = toCommand(evalCase, config);
        int budget = properties.getEval().getDegradedRetry();
        SearchOutcome outcome = OfflineExecutionContext.runOffline(() -> retrievalService.search(kbId, command));
        int retries = 0;
        while (!outcome.getDegraded().isEmpty() && retries < budget) {
            retries++;
            outcome = OfflineExecutionContext.runOffline(() -> retrievalService.search(kbId, command));
        }

        List<RetrievalNodeView> nodes = outcome.getNodes();
        List<List<String>> candidateTextsPerRank = new ArrayList<>(nodes.size());
        List<String> docIdPerRank = new ArrayList<>(nodes.size());
        List<String> recalledChunkIds = new ArrayList<>(nodes.size());
        for (RetrievalNodeView node : nodes) {
            candidateTextsPerRank.add(childTextsOf(node));
            docIdPerRank.add(node.getDocId());
            recalledChunkIds.add(node.getChunkId());
        }

        List<EvalEvidence> evidences = evidencesOf(evalCase);
        CaseJudgment judgment;
        List<Double> overlapRatios = new ArrayList<>();
        if (evalCase.getAnchorType() == AnchorType.SPAN) {
            List<String> spans = evidences.stream().map(EvalEvidence::getSpan).toList();
            judgment = evalHitJudge.judgeSpanCase(spans, candidateTextsPerRank,
                    properties.getEval().getOverlapThreshold());
            for (String span : spans) {
                overlapRatios.add(bestOverlapRatio(candidateTextsPerRank, span));
            }
        } else {
            List<String> docIds = evidences.stream().map(EvalEvidence::getDocId).toList();
            judgment = evalHitJudge.judgeDocumentCase(docIds, docIdPerRank);
        }

        EvalJudgeService.JudgeOutcome judgeOutcome = null;
        if (judgeEnabled && evalCase.getExpectedAnswer() != null && !evalCase.getExpectedAnswer().isBlank()
                && evalJudgeService.isAvailable()) {
            List<String> allTexts = candidateTextsPerRank.stream().flatMap(List::stream).toList();
            judgeOutcome = evalJudgeService.judge(evalCase.getExpectedAnswer(), allTexts);
        }

        AnswerCaseOutcome answerOutcome = answerOneCase(evalCase, nodes, answerConfig);

        boolean multiTurn = evalCase.getMessages() != null && !evalCase.getMessages().isBlank();
        return new CaseOutcome(evalCase.getCaseId(), judgment, overlapRatios, recalledChunkIds,
                outcome.getDegraded(), retries, judgeOutcome, answerOutcome, evalCase.getAnchorType(), multiTurn);
    }

    private AnswerCaseOutcome answerOneCase(EvalCase evalCase, List<RetrievalNodeView> nodes,
                                            AnswerEvaluationConfig config) {
        if (config == null || !answerJudgeRequested(evalCase)) {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        String answer = answerGenerationService.generate(config.snapshot(), evalCase.getQuery(),
                messagesOf(evalCase), nodes);
        long elapsed = System.currentTimeMillis() - startedAt;
        int latencyMs = elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
        List<String> passages = nodes.stream().map(RetrievalNodeView::getContent).toList();
        FinalAnswerJudgeService.JudgeOutcome judgment = finalAnswerJudgeService.judge(
                evalCase.getExpectedAnswer(), Boolean.TRUE.equals(evalCase.getExpectedRefusal()),
                config.snapshot().promptOrDefaults().isCitationEnabled(), answer, passages);
        return new AnswerCaseOutcome(answer, latencyMs, judgment);
    }

    private double bestOverlapRatio(List<List<String>> candidateTextsPerRank, String span) {
        double best = 0.0d;
        for (List<String> rankTexts : candidateTextsPerRank) {
            for (String text : rankTexts) {
                best = Math.max(best, overlapRatioCalculator.overlapRatio(text, span));
            }
        }
        return best;
    }

    private List<String> childTextsOf(RetrievalNodeView node) {
        Object childrenRaw = node.getMetadata() == null ? null
                : node.getMetadata().get(RetrievalMetadataKeys.CHILDREN);
        if (!(childrenRaw instanceof List<?> children) || children.isEmpty()) {
            return List.of(node.getContent());
        }
        List<String> texts = new ArrayList<>(children.size());
        for (Object child : children) {
            if (child instanceof Map<?, ?> map) {
                Object content = map.get(RetrievalMetadataKeys.CHILD_CONTENT);
                if (content != null) {
                    texts.add(String.valueOf(content));
                }
            }
        }
        return texts.isEmpty() ? List.of(node.getContent()) : texts;
    }

    private RetrievalCommand toCommand(EvalCase evalCase, EvalRetrievalConfig config) {
        EvalMode mode = config.getMode();
        return RetrievalCommand.builder()
                .query(evalCase.getQuery())
                .messages(messagesOf(evalCase))
                .recallTopK(config.getRecallTopK())
                .topN(config.getTopN())
                .fusionMode(config.getFusion())
                .scoreThreshold(config.getScoreThreshold())
                .rewriteEnabled(config.getRewriteEnabled())
                .rerankEnabled(mode.rerankRequested())
                .bm25RouteEnabled(mode.bm25RouteEnabled())
                .vectorRouteEnabled(mode.vectorRouteEnabled())
                .build();
    }

    private List<ChatMessage> messagesOf(EvalCase evalCase) {
        if (evalCase.getMessages() == null || evalCase.getMessages().isBlank()) {
            return List.of();
        }
        List<ChatMessage> messages = JsonUtil.parse(evalCase.getMessages(), new TypeReference<List<ChatMessage>>() {
        });
        return messages == null ? List.of() : messages;
    }

    private List<EvalEvidence> evidencesOf(EvalCase evalCase) {
        List<EvalEvidence> evidences = JsonUtil.parse(evalCase.getEvidences(),
                new TypeReference<List<EvalEvidence>>() {
                });
        return evidences == null ? List.of() : evidences;
    }
}
