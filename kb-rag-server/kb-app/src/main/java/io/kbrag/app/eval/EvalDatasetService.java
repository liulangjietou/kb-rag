package io.kbrag.app.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.EvalCase;
import io.kbrag.domain.entity.EvalDataset;
import io.kbrag.domain.entity.EvalResult;
import io.kbrag.domain.entity.EvalRun;
import io.kbrag.domain.enums.AnchorType;
import io.kbrag.domain.enums.CaseSource;
import io.kbrag.domain.enums.CaseStatus;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.EvalCaseMapper;
import io.kbrag.domain.mapper.EvalDatasetMapper;
import io.kbrag.domain.mapper.EvalResultMapper;
import io.kbrag.domain.mapper.EvalRunMapper;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.EvalCaseInput;
import io.kbrag.domain.model.EvalEvidence;
import io.kbrag.domain.service.BizIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation data set and case management, requirement section 4.5.
 *
 * <p><b>{@code dataset_revision} is the compare endpoint's whole safety net</b> (requirement section
 * 4.6): every method that inserts, edits, deletes or flips the status of a case bumps it inside the
 * same transaction as the mutation, so a run's snapshotted revision can never drift from what the case
 * table actually held at that instant. {@code case_count} is kept in step for the same reason the
 * knowledge base keeps {@code config_stale} precomputed - the console lists many data sets at once and
 * must not recount their cases on every page load.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    /** 已解决问题也需要保留其回归依据，不能只保护未处理问题。 */
    private static final String CASE_QUALITY_REFERENCE = "SELECT 1 FROM t_kb_quality_issue qi "
            + "WHERE qi.case_id = t_kb_eval_case.case_id AND qi.deleted = 0";
    private static final String DATASET_QUALITY_REFERENCE = "SELECT 1 FROM t_kb_quality_issue qi "
            + "WHERE qi.dataset_id = t_kb_eval_dataset.dataset_id AND qi.deleted = 0";

    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final BizIdGenerator bizIdGenerator;

    /**
     * Creates an empty data set.
     *
     * @param kbId        owning knowledge base business id
     * @param name        display name
     * @param description free text description
     * @return created data set
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalDataset create(String kbId, String name, String description) {
        knowledgeBaseService.require(kbId);
        EvalDataset dataset = new EvalDataset();
        dataset.setDatasetId(bizIdGenerator.evalDatasetId());
        dataset.setKbId(kbId);
        dataset.setName(name);
        dataset.setDescription(description);
        dataset.setDatasetRevision(0);
        dataset.setCaseCount(0);
        evalDatasetMapper.insert(dataset);
        log.info("evaluation data set created, datasetId={}, kbId={}", dataset.getDatasetId(), kbId);
        return dataset;
    }

    /**
     * Lists the data sets of a knowledge base together with their latest run.
     *
     * @param kbId knowledge base business id
     * @return data sets, newest first
     */
    public List<DatasetView> list(String kbId) {
        List<EvalDataset> datasets = evalDatasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getKbId, kbId)
                .orderByDesc(EvalDataset::getId));
        List<DatasetView> views = new ArrayList<>(datasets.size());
        for (EvalDataset dataset : datasets) {
            views.add(new DatasetView(dataset, latestRun(dataset.getDatasetId())));
        }
        return views;
    }

    /**
     * Loads a data set together with its latest run.
     *
     * @param datasetId data set business id
     * @return detail view
     */
    public DatasetView detail(String datasetId) {
        EvalDataset dataset = require(datasetId);
        return new DatasetView(dataset, latestRun(datasetId));
    }

    /**
     * Loads a data set or fails.
     *
     * @param datasetId data set business id
     * @return data set
     */
    public EvalDataset require(String datasetId) {
        EvalDataset dataset = evalDatasetMapper.selectOne(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getDatasetId, datasetId)
                .last("limit 1"));
        if (dataset == null) {
            throw BizException.notFound("evaluation data set not found");
        }
        return dataset;
    }

    /** 在独立的一致性读取事务中取得集合修订号和输入，事务不会覆盖后续检索或模型调用。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public EvaluationInputs snapshotForRun(String datasetId) {
        EvalDataset dataset = require(datasetId);
        List<EvalCaseInput> inputs = evalCaseMapper.selectList(new LambdaQueryWrapper<EvalCase>()
                        .eq(EvalCase::getDatasetId, datasetId)
                        .ne(EvalCase::getStatus, CaseStatus.DEPRECATED)
                        .orderByAsc(EvalCase::getId))
                .stream().map(EvalCaseInput::capture).toList();
        return new EvaluationInputs(dataset, inputs);
    }

    /** 集合元数据只用于建立运行；输入内容使用不可变值对象跨越排队边界。 */
    public record EvaluationInputs(EvalDataset dataset, List<EvalCaseInput> cases) { }

    /**
     * Deletes a data set together with every case, run and result it owns.
     *
     * <p>All soft deletes inside one transaction; unlike a knowledge base or a document delete, nothing
     * here reaches a search engine, so there is no after-commit half to schedule.
     *
     * @param datasetId data set business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String datasetId) {
        EvalDataset dataset = require(datasetId);
        // 先按修订号和引用关系取得删除资格；后续级联写入失败时整个事务回滚。
        requireDeleted(evalDatasetMapper.delete(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getId, dataset.getId())
                .eq(EvalDataset::getLockVersion, dataset.getLockVersion())
                .notExists(DATASET_QUALITY_REFERENCE)));
        List<EvalRun> runs = evalRunMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId));
        for (EvalRun run : runs) {
            evalResultMapper.delete(new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getRunId, run.getRunId()));
        }
        evalRunMapper.delete(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getDatasetId, datasetId));
        evalCaseMapper.delete(new LambdaQueryWrapper<EvalCase>().eq(EvalCase::getDatasetId, datasetId));
        log.info("evaluation data set deleted, datasetId={}, cascadedRuns={}", datasetId, runs.size());
    }

    /**
     * Adds a case to a data set.
     *
     * @param datasetId data set business id
     * @param command   case payload
     * @return created case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase createCase(String datasetId, EvalCaseCommand command) {
        EvalDataset dataset = require(datasetId);
        validate(command);
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(bizIdGenerator.evalCaseId());
        evalCase.setDatasetId(datasetId);
        applyCommand(evalCase, command, CaseSource.MANUAL);
        evalCaseMapper.insert(evalCase);
        bumpRevision(dataset, 1);
        log.info("evaluation case created, caseId={}, datasetId={}", evalCase.getCaseId(), datasetId);
        return evalCase;
    }

    /**
     * Pages the cases of a data set.
     *
     * @param datasetId data set business id
     * @param status    optional status filter
     * @param page      one based page number
     * @param size      page size
     * @return page of cases
     */
    public IPage<EvalCase> listCases(String datasetId, CaseStatus status, long page, long size) {
        require(datasetId);
        LambdaQueryWrapper<EvalCase> wrapper = new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getDatasetId, datasetId)
                .orderByDesc(EvalCase::getId);
        if (status != null) {
            wrapper.eq(EvalCase::getStatus, status);
        }
        return evalCaseMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * Replaces a case's payload.
     *
     * @param caseId  case business id
     * @param command new payload
     * @return updated case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase updateCase(String caseId, EvalCaseCommand command) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        validate(command);
        boolean wasDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        applyCommand(evalCase, command, evalCase.getSource());
        evalCase.setStatus(CaseStatus.ACTIVE);
        requireWritten(evalCaseMapper.updateById(evalCase));
        bumpRevision(dataset, wasDeprecated ? 1 : 0);
        log.info("evaluation case updated, caseId={}", caseId);
        return evalCase;
    }

    /**
     * Deletes a case.
     *
     * @param caseId case business id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(String caseId) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        requireDeleted(evalCaseMapper.delete(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getId, evalCase.getId())
                .eq(EvalCase::getLockVersion, evalCase.getLockVersion())
                .notExists(CASE_QUALITY_REFERENCE)));
        bumpRevision(dataset, evalCase.getStatus() == CaseStatus.DEPRECATED ? 0 : -1);
        log.info("evaluation case deleted, caseId={}", caseId);
    }

    /**
     * Reviews a stale case: re-anchors it to a fresh excerpt or retires it, requirement section 4.5.
     *
     * @param caseId    case business id
     * @param action    reviewer decision
     * @param evidences replacement evidence, required for {@link EvalRecheckAction#REANCHOR}
     * @return updated case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase recheck(String caseId, EvalRecheckAction action, List<EvalEvidence> evidences) {
        EvalCase evalCase = requireCase(caseId);
        EvalDataset dataset = require(evalCase.getDatasetId());
        boolean wasDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        if (action == EvalRecheckAction.DEPRECATE) {
            evalCase.setStatus(CaseStatus.DEPRECATED);
        } else {
            if (CollectionUtils.isEmpty(evidences)) {
                throw BizException.invalidParam("evidences are required to re-anchor a case");
            }
            evalCase.setEvidences(JsonUtil.toJson(resolveEvidences(evidences, evalCase.getAnchorType())));
            evalCase.setStatus(CaseStatus.ACTIVE);
        }
        requireWritten(evalCaseMapper.updateById(evalCase));
        boolean isDeprecated = evalCase.getStatus() == CaseStatus.DEPRECATED;
        int delta = wasDeprecated == isDeprecated ? 0 : (isDeprecated ? -1 : 1);
        bumpRevision(dataset, delta);
        log.info("evaluation case rechecked, caseId={}, action={}", caseId, action);
        return evalCase;
    }

    /**
     * Collects a one click case from the retrieval debug page, requirement section 4.5.
     *
     * @param datasetId       data set business id
     * @param query           query the debug page ran
     * @param messages        conversation history, may be empty
     * @param chunkIds        recalled chunks the operator selected as evidence
     * @param anchorOverride  forces {@code DOCUMENT} anchoring, {@code null} lets an image chunk decide
     * @return created case, {@code source=DEBUG_PAGE}
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase collectFromRetrieval(String datasetId, String query, List<ChatMessage> messages,
                                         List<String> chunkIds, AnchorType anchorOverride) {
        return collectFromRetrieval(datasetId, query, messages, chunkIds, anchorOverride,
                CaseSource.DEBUG_PAGE);
    }

    /**
     * Collects a case from recalled chunks with an explicit provenance, the M10 contract section 2.1.
     *
     * <p>Exists because the feedback conversion runs the exact collection path of the debug page but
     * must not claim to be it: {@code source} is the one column an analyst filters by when judging
     * where the cases of a data set came from.
     *
     * @param datasetId       data set business id
     * @param query           query the retrieval ran
     * @param messages        conversation history, may be empty
     * @param chunkIds        recalled chunks selected as evidence
     * @param anchorOverride  forces {@code DOCUMENT} anchoring, {@code null} lets an image chunk decide
     * @param source          how the case entered the data set
     * @return created case
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase collectFromRetrieval(String datasetId, String query, List<ChatMessage> messages,
                                         List<String> chunkIds, AnchorType anchorOverride,
                                         CaseSource source) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            throw BizException.invalidParam("chunk_ids must not be empty");
        }
        List<Chunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>().in(Chunk::getChunkId, chunkIds));
        if (chunks.size() != chunkIds.size()) {
            throw BizException.notFound("some selected chunks no longer exist");
        }
        // An image derived chunk carries no text worth quoting as a span, requirement section 4.5.
        boolean anyImage = chunks.stream().anyMatch(chunk -> chunk.getChunkType() == ChunkType.IMAGE);
        AnchorType anchorType = anchorOverride != null || anyImage ? AnchorType.DOCUMENT : AnchorType.SPAN;

        List<EvalEvidence> raw = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            EvalEvidence evidence = new EvalEvidence();
            evidence.setDocId(chunk.getDocId());
            evidence.setSpan(anchorType == AnchorType.SPAN ? chunk.getContent() : null);
            raw.add(evidence);
        }
        EvalCaseCommand command = EvalCaseCommand.builder()
                .query(query)
                .messages(messages)
                .anchorType(anchorType)
                .evidences(raw)
                .build();
        EvalDataset dataset = require(datasetId);
        validate(command);
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(bizIdGenerator.evalCaseId());
        evalCase.setDatasetId(datasetId);
        applyCommand(evalCase, command, source);
        evalCaseMapper.insert(evalCase);
        bumpRevision(dataset, 1);
        log.info("evaluation case collected from retrieval, caseId={}, datasetId={}, anchorType={}",
                evalCase.getCaseId(), datasetId, anchorType);
        return evalCase;
    }

    /**
     * Loads a case or fails.
     *
     * @param caseId case business id
     * @return case
     */
    public EvalCase requireCase(String caseId) {
        EvalCase evalCase = evalCaseMapper.selectOne(new LambdaQueryWrapper<EvalCase>()
                .eq(EvalCase::getCaseId, caseId)
                .last("limit 1"));
        if (evalCase == null) {
            throw BizException.notFound("evaluation case not found");
        }
        return evalCase;
    }

    private void requireDeleted(int written) {
        if (written != 1) {
            throw new BizException(ErrorCode.EVAL_DATASET_CONFLICT,
                    "该评测数据被质量问题引用或已被更新，删除未执行。请刷新后核对。");
        }
    }

    private void validate(EvalCaseCommand command) {
        if (command.getQuery() == null || command.getQuery().isBlank()) {
            throw BizException.invalidParam("query must not be blank");
        }
        if (command.getAnchorType() == null) {
            throw BizException.invalidParam("anchor_type is required");
        }
        if (CollectionUtils.isEmpty(command.getEvidences()) && !command.isExpectedRefusal()) {
            throw BizException.invalidParam("at least one evidence is required");
        }
        if (command.getAnchorType() == AnchorType.SPAN && CollectionUtils.isNotEmpty(command.getEvidences())) {
            for (EvalEvidence evidence : command.getEvidences()) {
                if (evidence.getSpan() == null || evidence.getSpan().isBlank()) {
                    throw BizException.invalidParam("a span anchored case requires a non blank span");
                }
            }
        }
    }

    private void applyCommand(EvalCase evalCase, EvalCaseCommand command, CaseSource source) {
        evalCase.setQuery(command.getQuery());
        evalCase.setMessages(CollectionUtils.isEmpty(command.getMessages())
                ? null : JsonUtil.toJson(command.getMessages()));
        evalCase.setExpectedAnswer(command.getExpectedAnswer());
        evalCase.setExpectedRefusal(command.isExpectedRefusal());
        evalCase.setAnchorType(command.getAnchorType());
        evalCase.setEvidences(JsonUtil.toJson(CollectionUtils.isEmpty(command.getEvidences())
                ? List.of() : resolveEvidences(command.getEvidences(), command.getAnchorType())));
        evalCase.setSource(source);
        evalCase.setNote(command.getNote());
        if (evalCase.getStatus() == null) {
            evalCase.setStatus(CaseStatus.ACTIVE);
        }
    }

    /**
     * Fills {@code annotated_version_id} from each evidence document's current active version -
     * the one field a caller must never be trusted to supply itself, since it is provenance about what
     * the server actually saw.
     *
     * @param evidences  evidence anchors, {@code annotated_version_id} ignored on input
     * @param anchorType anchoring granularity of the case
     * @return evidences with their provenance resolved
     */
    private List<EvalEvidence> resolveEvidences(List<EvalEvidence> evidences, AnchorType anchorType) {
        Map<String, Document> byDocId = new HashMap<>();
        List<EvalEvidence> resolved = new ArrayList<>(evidences.size());
        for (EvalEvidence evidence : evidences) {
            Document document = byDocId.computeIfAbsent(evidence.getDocId(), this::requireDocument);
            EvalEvidence copy = new EvalEvidence();
            copy.setDocId(evidence.getDocId());
            copy.setSpan(anchorType == AnchorType.DOCUMENT ? null : evidence.getSpan());
            copy.setAnnotatedVersionId(document.getCurrentVersionId());
            resolved.add(copy);
        }
        return resolved;
    }

    private Document requireDocument(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("evidence document not found: " + docId);
        }
        return document;
    }

    private EvalRun latestRun(String datasetId) {
        return evalRunMapper.selectOne(new LambdaQueryWrapper<EvalRun>()
                .eq(EvalRun::getDatasetId, datasetId)
                .orderByDesc(EvalRun::getId)
                .last("limit 1"));
    }

    /** 集合乐观锁冲突时回滚整笔用例变更，保证内容、修订号和计数始终一起提交。 */
    private void bumpRevision(EvalDataset dataset, int caseCountDelta) {
        dataset.setDatasetRevision((dataset.getDatasetRevision() == null ? 0 : dataset.getDatasetRevision()) + 1);
        dataset.setCaseCount(Math.max(0, (dataset.getCaseCount() == null ? 0 : dataset.getCaseCount())
                + caseCountDelta));
        requireWritten(evalDatasetMapper.updateById(dataset));
    }

    /** MyBatis 乐观锁未命中只返回零；显式抛错才能触发当前事务回滚。 */
    private void requireWritten(int affectedRows) {
        if (affectedRows != 1) {
            throw new BizException(ErrorCode.EVAL_DATASET_CONFLICT, "评测用例已被其他操作修改，请刷新后重新提交");
        }
    }

    /**
     * Data set together with the summary of its most recent run.
     *
     * @param dataset  data set row
     * @param lastRun  most recent run, {@code null} when none was executed yet
     */
    public record DatasetView(EvalDataset dataset, EvalRun lastRun) {
    }
}
