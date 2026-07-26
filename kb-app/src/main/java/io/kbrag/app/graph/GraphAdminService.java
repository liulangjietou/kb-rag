package io.kbrag.app.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.GraphEntityChunkRef;
import io.kbrag.domain.model.GraphEntityView;
import io.kbrag.domain.model.GraphSummary;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read and control surface of the knowledge graph of one knowledge base, requirement section 4.9.
 *
 * <p><b>Where the graph stops being trusted.</b> Everything the console displays about a chunk - its text,
 * its document, whether an operator disabled it - is read from MySQL, and the graph only supplies the
 * chunk id. That is the same rule the graph route follows, and it is why an entity whose passage was
 * disabled shows up here with {@code enabled=false} instead of quietly disappearing: the drill down is
 * what an operator uses to understand why a recall did not happen.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphAdminService {

    private static final int ENABLED = 1;

    private final KnowledgeBaseService knowledgeBaseService;
    private final GraphExtractionService graphExtractionService;
    private final GraphStore graphStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final KbTaskMapper kbTaskMapper;

    /**
     * Flips the graph switch of a knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param enabled new switch value
     * @return retrieval configuration as it was stored
     */
    public KbRetrievalConfig updateGraphEnabled(String kbId, boolean enabled) {
        return knowledgeBaseService.updateGraphEnabled(kbId, enabled);
    }

    /**
     * Triggers a full re-extraction of the graph of a knowledge base.
     *
     * <p>Refuses before creating a task rather than failing inside the worker: an operator pressed a
     * button and has to be told why nothing will happen, and a task row whose only content is "no graph
     * configured" is noise in the task monitor.
     *
     * @param kbId knowledge base business id
     * @return business id of the task doing the work
     */
    public String triggerExtraction(String kbId) {
        knowledgeBaseService.require(kbId);
        if (!graphStore.isEnabled()) {
            throw BizException.invalidParam("知识图谱未配置（NEO4J_URI 为空），无法触发抽取");
        }
        if (!knowledgeBaseService.graphEnabled(kbId)) {
            throw BizException.invalidParam("该知识库未开启图谱抽取开关");
        }
        KbTask task = graphExtractionService.openTask(kbId);
        graphExtractionService.runFullExtraction(kbId, task);
        log.info("graph extraction triggered, kbId={}, taskId={}", kbId, task.getTaskId());
        return task.getTaskId();
    }

    /**
     * Reads the size of the graph of a knowledge base and the state of its last extraction.
     *
     * @param kbId knowledge base business id
     * @return summary view
     */
    public GraphSummaryView summary(String kbId) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.require(kbId);
        GraphSummary counts = graphStore.summary(kbId);
        return new GraphSummaryView(knowledgeBaseService.retrievalConfigOf(knowledgeBase).graphEnabled(),
                counts, latestTask(kbId));
    }

    /**
     * Lists the entities of a knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param keyword name filter, blank matches everything
     * @param page    one based page number
     * @param size    page size
     * @return entities of the page
     */
    public List<GraphEntityView> listEntities(String kbId, String keyword, int page, int size) {
        knowledgeBaseService.require(kbId);
        return graphStore.listEntities(kbId, keyword, (page - 1) * size, size);
    }

    /**
     * Counts the entities a listing would match.
     *
     * @param kbId    knowledge base business id
     * @param keyword name filter, blank matches everything
     * @return total matching entities
     */
    public long countEntities(String kbId, String keyword) {
        return graphStore.countEntities(kbId, keyword);
    }

    /**
     * Reads the source passages of one entity.
     *
     * @param kbId       knowledge base business id
     * @param entityName exact entity name
     * @param limit      rows to return
     * @return passages the entity was extracted from, in graph order
     */
    public List<GraphEntityChunkView> chunksOf(String kbId, String entityName, int limit) {
        knowledgeBaseService.require(kbId);
        List<GraphEntityChunkRef> refs = graphStore.chunksOf(kbId, entityName, limit);
        if (CollectionUtils.isEmpty(refs)) {
            return List.of();
        }
        List<String> chunkIds = refs.stream().map(GraphEntityChunkRef::chunkId).toList();
        Map<String, Chunk> chunkById = new HashMap<>(chunkIds.size());
        for (Chunk chunk : chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKbId, kbId).in(Chunk::getChunkId, chunkIds))) {
            chunkById.put(chunk.getChunkId(), chunk);
        }
        Map<String, String> fileNameByDoc = fileNames(chunkById.values());
        Map<String, String> labelByVersion = versionLabels(chunkById.values());

        List<GraphEntityChunkView> views = new ArrayList<>(refs.size());
        for (GraphEntityChunkRef ref : refs) {
            Chunk chunk = chunkById.get(ref.chunkId());
            if (chunk == null) {
                // The graph still points at a chunk MySQL no longer owns. Dropping it is enough: the
                // removal cascade is what repairs the graph, and a read must not start writing.
                continue;
            }
            views.add(new GraphEntityChunkView(chunk.getChunkId(), chunk.getDocId(),
                    fileNameByDoc.get(chunk.getDocId()), chunk.getDocumentVersionId(),
                    labelByVersion.get(chunk.getDocumentVersionId()), chunk.getContent(),
                    chunk.getEnabled() != null && chunk.getEnabled() == ENABLED));
        }
        return views;
    }

    /**
     * Most recent extraction task of a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return task row, {@code null} when no extraction ever ran
     */
    private KbTask latestTask(String kbId) {
        return kbTaskMapper.selectOne(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getBizId, kbId)
                .eq(KbTask::getTaskType, TaskType.GRAPH_EXTRACT)
                .orderByDesc(KbTask::getId)
                .last("limit 1"));
    }

    private Map<String, String> fileNames(java.util.Collection<Chunk> chunks) {
        List<String> docIds = chunks.stream().map(Chunk::getDocId).distinct().toList();
        if (CollectionUtils.isEmpty(docIds)) {
            return Map.of();
        }
        Map<String, String> byDoc = new HashMap<>(docIds.size());
        for (Document document : documentMapper.selectList(
                new LambdaQueryWrapper<Document>().in(Document::getDocId, docIds))) {
            byDoc.put(document.getDocId(), document.getFileName());
        }
        return byDoc;
    }

    private Map<String, String> versionLabels(java.util.Collection<Chunk> chunks) {
        List<String> versionIds = chunks.stream().map(Chunk::getDocumentVersionId).distinct().toList();
        if (CollectionUtils.isEmpty(versionIds)) {
            return Map.of();
        }
        Map<String, String> byVersion = new HashMap<>(versionIds.size());
        for (DocumentVersion version : documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().in(DocumentVersion::getVersionId, versionIds))) {
            byVersion.put(version.getVersionId(), version.getVersion());
        }
        return byVersion;
    }
}
