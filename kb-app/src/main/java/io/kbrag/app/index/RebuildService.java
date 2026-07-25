package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RebuildService {

    private static final int STALE = 1;

    private final DocumentMapper documentMapper;
    private final IndexPipelineService indexPipelineService;

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
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>().eq(Document::getKbId, kbId);
        if (CollectionUtils.isEmpty(docIds)) {
            wrapper.eq(Document::getConfigStale, STALE);
        } else {
            wrapper.in(Document::getDocId, docIds);
        }
        List<Document> documents = documentMapper.selectList(wrapper);
        if (CollectionUtils.isNotEmpty(docIds) && documents.size() != docIds.size()) {
            throw BizException.notFound("some documents do not belong to this knowledge base");
        }
        return documents;
    }
}
