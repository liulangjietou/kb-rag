package io.kbrag.api.controller;

import io.kbrag.api.annotation.AuditedOperation;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AnnotationMigrationResponse;
import io.kbrag.api.dto.MigrateAnnotationRequest;
import io.kbrag.app.annotation.AnnotationMigrationService;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Annotation level endpoints of the review workbench, requirement section 4.5.
 *
 * <p>Deliberately one endpoint and no batch variant: a migration is a human decision about one annotation
 * and one chunk, and a batch call would turn a list of recommendations into an automatic apply through the
 * back door - the exact thing the requirement rules out.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/annotations")
@RequiredArgsConstructor
public class AnnotationController {

    private final AnnotationMigrationService annotationMigrationService;
    private final KbResourceGuard kbResourceGuard;

    /**
     * Applies an annotation of an older version to a chunk of the active one and closes the review item.
     *
     * @param annotationId annotation business id
     * @param request      target chunk
     * @return what was replayed and the state the annotation is now in
     */
    @PostMapping("/{annotationId}/migrate")
    @RequiresPermission(PermissionCodes.DOC_WRITE)
    @AuditedOperation(module = "ANNOTATION", action = "MIGRATE", targetType = "ANNOTATION",
            targetId = "#annotationId")
    public Result<AnnotationMigrationResponse> migrate(@PathVariable String annotationId,
                                                       @Valid @RequestBody MigrateAnnotationRequest request) {
        kbResourceGuard.requireAnnotationAccess(annotationId);
        return Result.success(AnnotationMigrationResponse.from(
                annotationMigrationService.migrate(annotationId, request.targetChunkId())));
    }
}
