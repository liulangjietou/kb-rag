package io.kbrag.app.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.eval.EvalDatasetService;
import io.kbrag.app.openapi.ApiKeyPrincipal;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.entity.SearchInsight;
import io.kbrag.domain.enums.CaseSource;
import io.kbrag.domain.enums.FeedbackChannel;
import io.kbrag.domain.enums.FeedbackStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.RetrievalFeedbackMapper;
import io.kbrag.domain.mapper.SearchInsightMapper;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists and manages the good/bad verdicts of the debug page, requirement section 4.5 "distilled
 * into evaluation material" and the M10 contract section 2.1.
 *
 * <p><b>A feedback is an event, not a state.</b> Submitting the same verdict twice records two rows on
 * purpose: each click is one observation, and deduplicating them would make five operators agreeing
 * look identical to one.
 *
 * <p><b>Only {@code GOOD} converts.</b> A conversion creates a case whose evidence is the chunk the
 * verdict pointed at; a {@code BAD} verdict states that this chunk is <em>not</em> evidence, so a case
 * built from it would assert the opposite of what the operator said. The value of {@code BAD} lives in
 * the statistics and the insight report instead.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalFeedbackService {

    /** Widest caller comment stored, matching the note column; the rest is cut, not rejected. */
    private static final int NOTE_MAX_LENGTH = 512;

    private final RetrievalFeedbackMapper retrievalFeedbackMapper;
    private final ChunkMapper chunkMapper;
    private final SearchInsightMapper searchInsightMapper;
    private final EvalDatasetService evalDatasetService;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Records one verdict.
     *
     * <p>The owning document is resolved server side from the chunk. A missing chunk does not reject
     * the feedback - the signal may arrive after the chunk was deleted, and the query text is still a
     * fact worth keeping - the row simply carries no {@code doc_id}.
     *
     * @param kbId    knowledge base the query ran against
     * @param query   query the debug page ran, stored raw for a later conversion
     * @param chunkId chunk the verdict concerns
     * @param verdict operator verdict
     * @return persisted row, {@code status=NEW}
     */
    public RetrievalFeedback record(String kbId, String query, String chunkId, FeedbackVerdict verdict) {
        RetrievalFeedback feedback = new RetrievalFeedback();
        feedback.setFeedbackId(bizIdGenerator.retrievalFeedbackId());
        feedback.setKbId(kbId);
        feedback.setQuery(query);
        feedback.setChunkId(chunkId);
        feedback.setDocId(resolveDocId(chunkId));
        feedback.setVerdict(verdict);
        feedback.setStatus(FeedbackStatus.NEW);
        feedback.setChannel(FeedbackChannel.CONSOLE);
        retrievalFeedbackMapper.insert(feedback);
        log.info("retrieval feedback recorded, feedbackId={}, kbId={}, verdict={}",
                feedback.getFeedbackId(), kbId, verdict);
        return feedback;
    }

    /**
     * Records one verdict arriving through the open API, the M16 contract section 7.
     *
     * <p>The caller names a {@code request_id}, never a knowledge base: the knowledge base and the
     * query are looked up from the insight row it points at. A feedback that cannot be tied to a
     * recorded retrieval is refused - anonymous verdicts can never be converted into evaluation
     * material, which makes them noise, not data.
     *
     * <p><b>The request id is not a credential.</b> It travels in the {@code X-Request-Id} header, so a
     * caller picks its own; on its own it proves nothing about who ran the retrieval. What authorises
     * the write is the application recorded on the insight row being inside this key's scope, which is
     * why the insight carries an {@code app_id} and why a console debug search - it has none - can
     * never be commented on through this channel. Without that check any key holder who learned
     * another application's request id could write verdicts against a knowledge base it cannot read.
     *
     * <p>The stored query is the insight digest, masked and truncated, not the raw text: the open
     * API channel must not become a path around the insight masking rules.
     *
     * @param principal authenticated caller, the source of the authorisation scope
     * @param requestId correlation id of the retrieval the verdict concerns
     * @param chunkId   chunk the verdict concerns, must belong to the retrieved knowledge base
     * @param verdict   end user verdict
     * @param comment   free form comment, truncated to the note column width, optional
     * @param endUserId caller asserted end user identifier, optional, not vouched for
     * @return persisted row, {@code status=NEW}, {@code channel=OPEN_API}
     */
    public RetrievalFeedback recordFromOpenApi(ApiKeyPrincipal principal, String requestId, String chunkId,
                                               FeedbackVerdict verdict, String comment, String endUserId) {
        SearchInsight insight = searchInsightMapper.selectOne(new LambdaQueryWrapper<SearchInsight>()
                .eq(SearchInsight::getRequestId, requestId)
                .isNotNull(SearchInsight::getAppId)
                .last("limit 1"));
        if (insight == null) {
            // Unknown, purged by the insight retention window, or a console debug search which this
            // channel does not cover; the message deliberately does not distinguish them, since none
            // is actionable differently by the caller.
            throw BizException.invalidParam("request_id 无效或已过期");
        }
        // The one authorisation gate of this channel, reusing the single scope check of the key.
        principal.requireAccessTo(insight.getAppId());
        RetrievalFeedback feedback = new RetrievalFeedback();
        feedback.setFeedbackId(bizIdGenerator.retrievalFeedbackId());
        feedback.setKbId(insight.getKbId());
        feedback.setQuery(insight.getQueryDigest());
        feedback.setChunkId(chunkId);
        feedback.setDocId(resolveOpenApiDocId(insight.getKbId(), chunkId));
        feedback.setVerdict(verdict);
        feedback.setStatus(FeedbackStatus.NEW);
        feedback.setChannel(FeedbackChannel.OPEN_API);
        feedback.setEndUserId(endUserId);
        if (comment != null && !comment.isBlank()) {
            String trimmed = comment.trim();
            feedback.setNote(trimmed.length() > NOTE_MAX_LENGTH
                    ? trimmed.substring(0, NOTE_MAX_LENGTH)
                    : trimmed);
        }
        retrievalFeedbackMapper.insert(feedback);
        log.info("open api feedback recorded, feedbackId={}, kbId={}, verdict={}, requestId={}",
                feedback.getFeedbackId(), insight.getKbId(), verdict, requestId);
        return feedback;
    }

    /**
     * Pages the feedback of one knowledge base, newest first.
     *
     * @param kbId    knowledge base business id
     * @param verdict optional verdict filter
     * @param status  optional status filter
     * @param channel optional channel filter
     * @param page    one based page number
     * @param size    page size
     * @return paged rows
     */
    public IPage<RetrievalFeedback> list(String kbId, FeedbackVerdict verdict, FeedbackStatus status,
                                         FeedbackChannel channel, long page, long size) {
        LambdaQueryWrapper<RetrievalFeedback> wrapper = new LambdaQueryWrapper<RetrievalFeedback>()
                .eq(RetrievalFeedback::getKbId, kbId);
        if (verdict != null) {
            wrapper.eq(RetrievalFeedback::getVerdict, verdict);
        }
        if (status != null) {
            wrapper.eq(RetrievalFeedback::getStatus, status);
        }
        if (channel != null) {
            wrapper.eq(RetrievalFeedback::getChannel, channel);
        }
        return retrievalFeedbackMapper.selectPage(new Page<>(page, size),
                wrapper.orderByDesc(RetrievalFeedback::getId));
    }

    /**
     * Converts one {@code GOOD} feedback into an evaluation case, the closing move of the loop.
     *
     * <p>Delegates to the exact collection path of the debug page so a converted case is structurally
     * indistinguishable from a hand collected one, differing only in its {@code source=FEEDBACK}
     * provenance. A deleted chunk fails the conversion with a clear message rather than silently
     * producing a case without evidence.
     *
     * @param feedbackId feedback business id, must be {@code NEW} and {@code GOOD}
     * @param datasetId  target evaluation data set
     * @return the same row, {@code status=CONVERTED} with the case id filled in
     */
    @Transactional(rollbackFor = Exception.class)
    public RetrievalFeedback convert(String feedbackId, String datasetId) {
        RetrievalFeedback feedback = require(feedbackId);
        requireNew(feedback);
        if (feedback.getVerdict() == FeedbackVerdict.BAD) {
            throw BizException.invalidParam("BAD 反馈不能转为评测用例：它声明该分片不是证据，其价值在洞察统计");
        }
        // Checked here rather than left to the collector: its "some selected chunks no longer exist"
        // is phrased for an operator holding a chunk list, and letting it pass through would also
        // let a missing data set masquerade as a missing chunk.
        if (chunkOf(feedback.getChunkId()) == null) {
            throw BizException.invalidParam("反馈引用的分片已不存在，无法转为评测用例");
        }
        EvalCase evalCase = evalDatasetService.collectFromRetrieval(datasetId, feedback.getQuery(), null,
                List.of(feedback.getChunkId()), null, CaseSource.FEEDBACK);
        feedback.setStatus(FeedbackStatus.CONVERTED);
        feedback.setConvertedCaseId(evalCase.getCaseId());
        retrievalFeedbackMapper.updateById(feedback);
        log.info("retrieval feedback converted, feedbackId={}, caseId={}, datasetId={}",
                feedbackId, evalCase.getCaseId(), datasetId);
        return feedback;
    }

    /**
     * Dismisses one feedback, the terminal "seen, not worth a case" decision.
     *
     * @param feedbackId feedback business id, must be {@code NEW}
     * @return the same row, {@code status=DISMISSED}
     */
    public RetrievalFeedback dismiss(String feedbackId) {
        RetrievalFeedback feedback = require(feedbackId);
        requireNew(feedback);
        feedback.setStatus(FeedbackStatus.DISMISSED);
        retrievalFeedbackMapper.updateById(feedback);
        log.info("retrieval feedback dismissed, feedbackId={}", feedbackId);
        return feedback;
    }

    /**
     * Loads a feedback row or fails.
     *
     * @param feedbackId feedback business id
     * @return feedback row
     */
    public RetrievalFeedback require(String feedbackId) {
        RetrievalFeedback feedback = retrievalFeedbackMapper.selectOne(
                new LambdaQueryWrapper<RetrievalFeedback>()
                        .eq(RetrievalFeedback::getFeedbackId, feedbackId)
                        .last("limit 1"));
        if (feedback == null) {
            throw BizException.notFound("retrieval feedback not found");
        }
        return feedback;
    }

    /**
     * Guards the one legal transition source: both non {@code NEW} states are terminal, and repeating
     * an operation on them must fail loudly rather than silently succeed twice.
     *
     * @param feedback row about to change state
     */
    private void requireNew(RetrievalFeedback feedback) {
        if (feedback.getStatus() != FeedbackStatus.NEW) {
            throw BizException.invalidParam("反馈已处于终态 " + feedback.getStatus() + "，不能再变更");
        }
    }

    private String resolveDocId(String chunkId) {
        Chunk chunk = chunkOf(chunkId);
        if (chunk == null) {
            log.info("feedback chunk no longer exists, recorded without doc id, chunkId={}", chunkId);
            return null;
        }
        return chunk.getDocId();
    }

    /**
     * Resolves the owning document of an open API verdict, refusing a chunk of another knowledge base.
     *
     * <p>A chunk that no longer exists keeps the console semantics - the verdict is still a fact, the
     * row simply carries no {@code doc_id} - because a signal may legitimately arrive after a deletion.
     * A chunk that exists elsewhere is a different matter: nothing legitimate produces it, since the
     * retrieval this verdict names only ever returned chunks of {@code kbId}, so it is refused rather
     * than stored as a verdict whose evidence points into a corpus the caller was never served.
     *
     * @param kbId    knowledge base the named retrieval ran against
     * @param chunkId chunk the verdict concerns
     * @return owning document id, {@code null} when the chunk is gone
     */
    private String resolveOpenApiDocId(String kbId, String chunkId) {
        Chunk chunk = chunkOf(chunkId);
        if (chunk == null) {
            log.info("feedback chunk no longer exists, recorded without doc id, chunkId={}", chunkId);
            return null;
        }
        if (!kbId.equals(chunk.getKbId())) {
            log.error("open api feedback names a chunk of another knowledge base, errorCode={}, "
                            + "kbId={}, chunkId={}",
                    ErrorCode.FORBIDDEN, kbId, chunkId);
            throw BizException.invalidParam("chunk_id 不属于该次检索的知识库");
        }
        return chunk.getDocId();
    }

    private Chunk chunkOf(String chunkId) {
        return chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getChunkId, chunkId)
                .last("limit 1"));
    }
}
