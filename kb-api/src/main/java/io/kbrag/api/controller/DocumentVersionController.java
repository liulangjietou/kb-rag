package io.kbrag.api.controller;

import io.kbrag.api.dto.ActivateImpactResponse;
import io.kbrag.api.dto.ActivateVersionResponse;
import io.kbrag.api.dto.DocumentVersionResponse;
import io.kbrag.api.dto.PendingAnnotationResponse;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.document.DocumentVersionService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.entity.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Document version management endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/documents/{docId}")
@RequiredArgsConstructor
public class DocumentVersionController {

    private final DocumentService documentService;
    private final DocumentVersionService documentVersionService;
    private final AnnotationInheritanceService annotationInheritanceService;

    /**
     * Lists the versions of a document, newest first.
     *
     * @param docId document business id
     * @return versions with their chunk count and the cost of activating them
     */
    @GetMapping("/versions")
    public Result<List<DocumentVersionResponse>> versions(@PathVariable String docId) {
        return Result.success(documentVersionService.list(docId).stream()
                .map(DocumentVersionResponse::from).toList());
    }

    /**
     * Reports what activating a version would cost, for the confirmation dialog.
     *
     * @param docId     document business id
     * @param versionId target version business id
     * @return pre-flight summary
     */
    @GetMapping("/versions/{versionId}/activate-impact")
    public Result<ActivateImpactResponse> activateImpact(@PathVariable String docId,
                                                         @PathVariable String versionId) {
        return Result.success(ActivateImpactResponse.from(
                documentVersionService.activateImpact(docId, versionId)));
    }

    /**
     * Switches the active version of a document.
     *
     * @param docId     document business id
     * @param versionId target version business id
     * @return the applied mode and the rebuild task id, {@code null} when the switch already happened
     */
    @PostMapping("/versions/{versionId}/activate")
    public Result<ActivateVersionResponse> activate(@PathVariable String docId,
                                                    @PathVariable String versionId) {
        return Result.success(ActivateVersionResponse.from(
                documentVersionService.activate(docId, versionId)));
    }

    /**
     * Lists the manual operations of older versions that the active version does not carry.
     *
     * <p>Only genuinely open items are returned - nothing that was inherited automatically or performed
     * again - so the length of the list is the number the console shows.
     *
     * @param docId document business id
     * @return review items, newest first
     */
    @GetMapping("/annotations/pending-review")
    public Result<List<PendingAnnotationResponse>> pendingReview(@PathVariable String docId) {
        Document document = documentService.require(docId);
        return Result.success(annotationInheritanceService
                .pendingReview(docId, document.getCurrentVersionId()).stream()
                .map(PendingAnnotationResponse::from).toList());
    }
}
