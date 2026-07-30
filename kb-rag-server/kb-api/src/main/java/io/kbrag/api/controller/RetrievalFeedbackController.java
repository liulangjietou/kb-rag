package io.kbrag.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.ConvertFeedbackRequest;
import io.kbrag.api.dto.PageResponse;
import io.kbrag.api.dto.RetrievalFeedbackRequest;
import io.kbrag.api.dto.RetrievalFeedbackResponse;
import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbScopeGuard;
import io.kbrag.app.feedback.RetrievalFeedbackService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.RetrievalFeedback;
import io.kbrag.domain.enums.FeedbackChannel;
import io.kbrag.domain.enums.FeedbackStatus;
import io.kbrag.domain.enums.FeedbackVerdict;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Good/bad feedback on debug page results, requirement section 4.5 and the M10 contract section 2.1.
 *
 * <p>The submission payload is unchanged from M4b - the compatibility line of the M10 contract - but
 * the behaviour is upgraded from "log and forget" to a persisted row the console can list, convert
 * into an evaluation case or dismiss.
 *
 * <p>Submitting accepts either {@code search:debug} or {@code feedback:manage}, because the signal is
 * produced on the debug page by whoever is tuning retrieval; triaging it afterwards is the narrower
 * {@code feedback:manage} alone, since converting a signal writes an evaluation case.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequiredArgsConstructor
public class RetrievalFeedbackController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final RetrievalFeedbackService retrievalFeedbackService;
    private final KbScopeGuard kbScopeGuard;

    /**
     * Records one feedback signal.
     *
     * @param request feedback payload
     * @return persisted row, {@code status=NEW}
     */
    @PostMapping("/api/v1/retrieval-feedback")
    @RequiresPermission({PermissionCodes.SEARCH_DEBUG, PermissionCodes.FEEDBACK_MANAGE})
    public Result<RetrievalFeedbackResponse> feedback(@Valid @RequestBody RetrievalFeedbackRequest request) {
        AccessGuard.requireKbAccess(request.kbId());
        FeedbackVerdict verdict = FeedbackVerdict.from(request.verdict());
        if (verdict == null) {
            throw BizException.invalidParam("verdict 仅支持 GOOD 或 BAD");
        }
        RetrievalFeedback feedback = retrievalFeedbackService.record(
                request.kbId(), request.query(), request.chunkId(), verdict);
        return Result.success(RetrievalFeedbackResponse.from(feedback));
    }

    /**
     * Pages the feedback of one knowledge base, newest first.
     *
     * @param kbId    knowledge base business id
     * @param verdict optional {@code GOOD} or {@code BAD} filter
     * @param status  optional {@code NEW}, {@code CONVERTED} or {@code DISMISSED} filter
     * @param channel optional {@code CONSOLE} or {@code OPEN_API} filter
     * @param page    one based page number
     * @param size    page size
     * @return paged rows
     */
    @GetMapping("/api/v1/kb/{kbId}/retrieval-feedback")
    @RequiresPermission(PermissionCodes.FEEDBACK_MANAGE)
    public Result<PageResponse<RetrievalFeedbackResponse>> list(
            @PathVariable String kbId,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(name = "page", defaultValue = "" + DEFAULT_PAGE) long page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) long size) {
        AccessGuard.requireKbAccess(kbId);
        IPage<RetrievalFeedback> paged = retrievalFeedbackService.list(kbId,
                parseVerdict(verdict), parseStatus(status), parseChannel(channel),
                normalizePage(page), normalizeSize(size));
        return Result.success(PageResponse.from(paged, RetrievalFeedbackResponse::from));
    }

    /**
     * Converts one {@code GOOD} feedback into an evaluation case.
     *
     * @param feedbackId feedback business id
     * @param request    target data set
     * @return the same row, {@code status=CONVERTED} with the case id filled in
     */
    @PostMapping("/api/v1/retrieval-feedback/{feedbackId}/convert")
    @RequiresPermission(PermissionCodes.FEEDBACK_MANAGE)
    public Result<RetrievalFeedbackResponse> convert(@PathVariable String feedbackId,
                                                     @Valid @RequestBody ConvertFeedbackRequest request) {
        // Both ends are checked: the signal's own knowledge base and the data set it is about to be
        // written into, which may well belong to another one.
        kbScopeGuard.requireFeedbackAccess(feedbackId);
        kbScopeGuard.requireDatasetAccess(request.datasetId());
        return Result.success(RetrievalFeedbackResponse.from(
                retrievalFeedbackService.convert(feedbackId, request.datasetId())));
    }

    /**
     * Dismisses one feedback.
     *
     * @param feedbackId feedback business id
     * @return the same row, {@code status=DISMISSED}
     */
    @PostMapping("/api/v1/retrieval-feedback/{feedbackId}/dismiss")
    @RequiresPermission(PermissionCodes.FEEDBACK_MANAGE)
    public Result<RetrievalFeedbackResponse> dismiss(@PathVariable String feedbackId) {
        kbScopeGuard.requireFeedbackAccess(feedbackId);
        return Result.success(RetrievalFeedbackResponse.from(
                retrievalFeedbackService.dismiss(feedbackId)));
    }

    private FeedbackVerdict parseVerdict(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        FeedbackVerdict verdict = FeedbackVerdict.from(value);
        if (verdict == null) {
            throw BizException.invalidParam("verdict 仅支持 GOOD 或 BAD");
        }
        return verdict;
    }

    private FeedbackStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        FeedbackStatus status = FeedbackStatus.from(value);
        if (status == null) {
            throw BizException.invalidParam("status 仅支持 NEW、CONVERTED 或 DISMISSED");
        }
        return status;
    }

    private FeedbackChannel parseChannel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        FeedbackChannel channel = FeedbackChannel.from(value);
        if (channel == null) {
            throw BizException.invalidParam("channel 仅支持 CONSOLE 或 OPEN_API");
        }
        return channel;
    }

    private long normalizePage(long page) {
        return page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    private long normalizeSize(long size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
