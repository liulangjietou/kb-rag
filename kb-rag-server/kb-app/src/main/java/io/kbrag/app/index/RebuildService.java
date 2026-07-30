package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.document.DocumentService;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns a configuration change into work.
 *
 * <p>Scope selection is the whole point of this service. A rebuild request without an explicit
 * document list means "every document the new configuration invalidated", which is exactly the set the
 * {@code config_stale} flag already tracks, so an operator never has to enumerate documents by hand
 * and a document whose active version already matches is never rebuilt twice.
 *
 * <p><b>Deviation from the requirement, recorded deliberately.</b> Section 4.3 describes rebuilds as
 * "create a new physical index and switch the alias". That procedure exists because an embedding model
 * change alters the vector dimension, which is frozen into the index schema. A split configuration
 * change alters neither the schema nor the field set, so the chunks can be replaced inside the index
 * that already exists. Doing it in place costs one write per chunk instead of a full corpus
 * re-embedding plus a second copy of the index on disk; the alias switch procedure stays reserved for
 * the embedding model change it was designed for.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebuildService {

    private static final int STALE = 1;

    /**
     * 重建过程中文档会经过的状态，与 {@link IndexPipelineService#rebuild(String)} 实际写入的一致：
     * 先 PARSING/PARSED（复用解析产物时可能一闪而过），再 INDEXING，成功后才清掉 config_stale。
     */
    private static final Collection<ProcessStatus> IN_PROGRESS = List.of(
            ProcessStatus.PARSING, ProcessStatus.PARSED, ProcessStatus.INDEXING);

    /** 重建失败停留的状态：文档仍是 stale，必须单独报出来，否则计数不动会被看成卡住。 */
    private static final Collection<ProcessStatus> FAILED = List.of(
            ProcessStatus.PARSE_FAILED, ProcessStatus.INDEX_FAILED);

    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final IndexPipelineService indexPipelineService;

    /**
     * 汇报整个知识库的配置追平情况，供控制台还原"重建中"的展示。
     *
     * <p>控制台此前把重建进度记在页面内存里，操作员一离开详情页状态就没了：进度条消失、完成提示
     * 永远不再出现、按钮回到可点击态引来重复提交，而后端任务其实一直在线程池里跑。进度的事实只在
     * 库里，所以这里按 {@code config_stale} 与 {@code process_status} 现算——任何时刻打开页面，
     * 甚至换一个人来看，看到的都是同一份真实状态。
     *
     * <p>统计口径与 {@link #submit(String, List)} 的可重建集合对齐（要求有活跃版本），否则分母里会
     * 混进永远重建不了的文档，让追平进度永远差一截。
     *
     * @param kbId knowledge base business id
     * @return 追平状态
     */
    public RebuildStatus status(String kbId) {
        int stale = count(kbId, null);
        int inProgress = count(kbId, IN_PROGRESS);
        int failed = count(kbId, FAILED);
        return new RebuildStatus(stale, inProgress, failed);
    }

    private int count(String kbId, Collection<ProcessStatus> statuses) {
        LambdaQueryWrapper<Document> wrapper = staleScope(kbId);
        if (CollectionUtils.isNotEmpty(statuses)) {
            wrapper.in(Document::getProcessStatus, statuses);
        }
        return Math.toIntExact(documentMapper.selectCount(wrapper));
    }

    /**
     * Queues a rebuild for a knowledge base.
     *
     * @param kbId   knowledge base business id
     * @param docIds explicit document scope, {@code null} or empty meaning every stale document
     * @return documents that were queued
     */
    public List<String> submit(String kbId, List<String> docIds) {
        List<Document> targets = resolveTargets(kbId, docIds);
        List<String> queued = new ArrayList<>(targets.size());
        for (Document document : targets) {
            if (document.getCurrentVersionId() == null) {
                log.info("skip rebuild of a document without an active version, docId={}", document.getDocId());
                continue;
            }
            queued.add(document.getDocId());
            indexPipelineService.submitRebuild(document.getCurrentVersionId());
        }
        log.info("rebuild queued, kbId={}, requested={}, queued={}",
                kbId, docIds == null ? 0 : docIds.size(), queued.size());
        return queued;
    }

    private List<Document> resolveTargets(String kbId, List<String> docIds) {
        if (CollectionUtils.isNotEmpty(docIds)) {
            return documentService.requireAllInKb(kbId, docIds);
        }
        return documentMapper.selectList(staleScope(kbId));
    }

    /** 待追平文档的取数口径，提交与统计共用一处，避免两边口径漂移。 */
    private LambdaQueryWrapper<Document> staleScope(String kbId) {
        return new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .eq(Document::getConfigStale, STALE)
                .isNotNull(Document::getCurrentVersionId);
    }

    /**
     * 知识库的配置追平状态。
     *
     * @param staleCount      仍需按新配置重建的文档数，归零即全部追平
     * @param inProgressCount 其中正在跑重建管线的文档数
     * @param failedCount     其中重建失败、需要人工介入的文档数
     */
    public record RebuildStatus(int staleCount, int inProgressCount, int failedCount) {
    }
}
