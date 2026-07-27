package io.kbrag.app.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.eval.EvalCaseStalenessService;
import io.kbrag.app.index.DocumentVersionActivator;
import io.kbrag.app.index.IndexPipelineService;
import io.kbrag.app.index.VersionRetentionService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.RollbackMode;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.port.VersionPinChecker;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Version list, activation and the pre-flight check behind the confirmation dialog.
 *
 * <p><b>Two kinds of activation, one endpoint.</b> A version that still owns its chunks is switched
 * synchronously and the caller gets no task; a version whose chunks the retention policy cleaned up
 * needs them produced again first, so a rebuild task is created and the switch happens when it
 * completes. The distinction is visible to the caller as {@code rollback_mode} rather than as two
 * endpoints, because it is a property of the target version and not of the operator's intention.
 *
 * <p><b>The task row is created here, not by the worker.</b> The console has to be handed a task id in
 * the same response that accepted the switch, and a worker that creates its own row can only be polled
 * after it has started. The pipeline picks up this row by document version and task type.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentVersionService {

    private final DocumentService documentService;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final KbTaskMapper kbTaskMapper;
    private final BizIdGenerator bizIdGenerator;
    private final DocumentVersionActivator versionActivator;
    private final IndexPipelineService indexPipelineService;
    private final VersionRetentionService versionRetentionService;
    private final AnnotationInheritanceService annotationInheritanceService;
    private final EvalCaseStalenessService evalCaseStalenessService;
    private final VersionPinChecker versionPinChecker;

    /**
     * Lists the versions of a document, newest first.
     *
     * @param docId document business id
     * @return version views carrying their chunk count and the cost of activating them
     */
    public List<VersionView> list(String docId) {
        Document document = documentService.require(docId);
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, docId));
        if (CollectionUtils.isEmpty(versions)) {
            return List.of();
        }
        List<DocumentVersion> ordered = new ArrayList<>(versions);
        ordered.sort(Comparator.comparing(DocumentVersion::getId,
                Comparator.nullsFirst(Long::compareTo)).reversed());
        // Asked once for the whole document rather than once per row: the answer is a scan of the application
        // versions holding a frozen visibility set, and repeating it per version would multiply that by the
        // retention window for an answer that cannot differ between rows.
        Map<String, List<String>> pinnedBy = versionPinChecker.pinnedBy(docId);
        List<VersionView> views = new ArrayList<>(ordered.size());
        for (DocumentVersion version : ordered) {
            long chunkCount = chunkCountOf(version.getVersionId());
            List<String> pins = pinnedBy.getOrDefault(version.getVersionId(), List.of());
            views.add(new VersionView(version, chunkCount,
                    version.getVersionId().equals(document.getCurrentVersionId()),
                    RollbackMode.resolve(version.getStatus(), chunkCount), pins));
        }
        return views;
    }

    /**
     * Reports what activating a version would cost and what it would leave open.
     *
     * @param docId     document business id
     * @param versionId target version business id
     * @return pre-flight summary of the switch
     */
    public ActivateImpact activateImpact(String docId, String versionId) {
        documentService.require(docId);
        DocumentVersion version = require(docId, versionId);
        RollbackMode mode = RollbackMode.resolve(version.getStatus(), chunkCountOf(versionId));
        int stale = annotationInheritanceService.staleCount(docId, versionId);
        int affectedEvalCases = evalCaseStalenessService.staleCount(docId, versionId);
        return new ActivateImpact(mode, stale, affectedEvalCases);
    }

    /**
     * Switches the active version of a document.
     *
     * @param docId     document business id
     * @param versionId target version business id
     * @return the mode that was applied and the rebuild task id when one was created
     */
    public Activation activate(String docId, String versionId) {
        Document document = documentService.require(docId);
        DocumentVersion version = require(docId, versionId);
        if (versionId.equals(document.getCurrentVersionId())) {
            throw BizException.invalidParam("this version is already the active one");
        }
        RollbackMode mode = RollbackMode.resolve(version.getStatus(), chunkCountOf(versionId));
        if (mode.needsRebuild()) {
            String taskId = createRebuildTask(versionId);
            indexPipelineService.submitRestore(versionId);
            log.info("version activation queued as a rebuild, docId={}, versionId={}, taskId={}",
                    docId, versionId, taskId);
            return new Activation(mode, taskId);
        }
        versionActivator.activate(document, version);
        annotationInheritanceService.inherit(document, version);
        evalCaseStalenessService.markStale(docId, versionId);
        versionRetentionService.submit(docId);
        log.info("version activated instantly, docId={}, versionId={}", docId, versionId);
        return new Activation(mode, null);
    }

    /**
     * Creates the task row the console polls before the worker starts.
     *
     * @param versionId version being rebuilt
     * @return task business id
     */
    private String createRebuildTask(String versionId) {
        KbTask task = new KbTask();
        task.setTaskId(bizIdGenerator.taskId());
        task.setTaskType(TaskType.REBUILD);
        task.setBizId(versionId);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setProgress(0);
        kbTaskMapper.insert(task);
        return task.getTaskId();
    }

    private long chunkCountOf(String versionId) {
        return chunkMapper.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocumentVersionId, versionId));
    }

    private DocumentVersion require(String docId, String versionId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getVersionId, versionId)
                .eq(DocumentVersion::getDocId, docId)
                .last("limit 1"));
        if (version == null) {
            throw BizException.notFound("document version not found");
        }
        return version;
    }

    /**
     * One row of the version list.
     *
     * @param version      version row
     * @param chunkCount   chunks the version still owns
     * @param active       {@code true} when the document points at this version
     * @param rollbackMode cost of activating it
     * @param pinnedBy     application versions whose index snapshot references this version, requirement
     *                     section 4.1 "archiving protection"; empty when nothing pins it
     */
    public record VersionView(DocumentVersion version, long chunkCount, boolean active,
                              RollbackMode rollbackMode, List<String> pinnedBy) {

        /**
         * Tells whether the retention cleanup will skip this version.
         *
         * @return {@code true} when at least one application version references it
         */
        public boolean pinned() {
            return !pinnedBy.isEmpty();
        }
    }

    /**
     * Pre-flight summary shown before a switch is confirmed.
     *
     * @param rollbackMode          cost of the switch
     * @param staleAnnotationCount  manual operations of other versions the target neither inherits nor repeats
     * @param affectedEvalCaseCount evaluation cases that would need a review, zero until they exist
     */
    public record ActivateImpact(RollbackMode rollbackMode, int staleAnnotationCount,
                                 int affectedEvalCaseCount) {
    }

    /**
     * Outcome of an activation request.
     *
     * @param rollbackMode mode that was applied
     * @param taskId       rebuild task, {@code null} when the switch already happened
     */
    public record Activation(RollbackMode rollbackMode, String taskId) {
    }
}
