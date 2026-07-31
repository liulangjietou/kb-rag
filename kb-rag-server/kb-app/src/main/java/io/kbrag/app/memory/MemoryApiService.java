package io.kbrag.app.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.entity.MemoryFragmentRule;
import io.kbrag.domain.entity.MemoryNode;
import io.kbrag.domain.entity.MemoryProfileRule;
import io.kbrag.domain.enums.MemoryEventType;
import io.kbrag.domain.enums.MemoryNodeSource;
import io.kbrag.domain.mapper.MemoryFragmentRuleMapper;
import io.kbrag.domain.mapper.MemoryNodeMapper;
import io.kbrag.domain.model.MemoryCommand;
import io.kbrag.domain.model.MemoryDoc;
import io.kbrag.domain.model.MemoryHit;
import io.kbrag.domain.model.MemorySearchQuery;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.MemoryStore;
import io.kbrag.domain.port.RerankProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.MemoryPromptAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The memory open API behind the key filter, the M19 contract: add, search, list, update, delete
 * and the profile read.
 *
 * <p><b>Isolation is a query predicate here, never a convention.</b> Every statement this class
 * issues filters by the library the authenticated key is bound to, and every entity-scoped one
 * additionally by the caller's {@code user_id}; a node outside that intersection does not exist as
 * far as the caller can observe, which is why ownership misses answer 404 and not 403.
 *
 * <p><b>Add is deliberately not one transaction.</b> The extraction calls sit between the writes,
 * and holding a connection open across an LLM round trip would starve the pool under modest load.
 * Each node write is atomic on its own, and the search copy follows each write immediately, so a
 * failure loses at most the tail of one call - which the caller sees as an error and may retry.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryApiService {

    /** Most old memories shown to a PRO extraction, newest first; the window UPDATE can target. */
    public static final int OLD_MEMORY_WINDOW = 50;

    /** Candidate multiplier when rerank is on, so the reranker has something to reorder. */
    private static final int RERANK_CANDIDATE_FACTOR = 3;

    /** Most candidates ever recalled from the index in one search. */
    private static final int MAX_CANDIDATES = 100;

    private final MemoryNodeMapper memoryNodeMapper;
    private final MemoryFragmentRuleMapper memoryFragmentRuleMapper;
    private final MemoryStore memoryStore;
    private final EmbeddingProvider embeddingProvider;
    private final RerankProvider rerankProvider;
    private final MemoryExtractionService memoryExtractionService;
    private final MemoryProfileService memoryProfileService;
    private final MemoryPromptAssembler memoryPromptAssembler;
    private final BizIdGenerator bizIdGenerator;

    /**
     * AddMemory: writes verbatim content, extracts from a conversation, or both.
     *
     * @param principal authenticated caller, source of the library
     * @param command   validated call payload
     * @return nodes written or revised, plus the profile when one was extracted
     */
    public MemoryAddOutcome add(MemoryKeyPrincipal principal, MemoryAddCommand command) {
        boolean hasMessages = command.messages() != null && !command.messages().isEmpty();
        boolean hasCustom = command.customContent() != null && !command.customContent().isBlank();
        if (!hasMessages && !hasCustom) {
            throw BizException.invalidParam("messages 与 custom_content 至少传其一");
        }
        if (command.profileRuleId() != null && !command.profileRuleId().isBlank() && !hasMessages) {
            throw BizException.invalidParam("profile_rule_id 需要同时传入 messages");
        }
        MemoryFragmentRule rule = resolveFragmentRule(principal.getLibraryId(), command.fragmentRuleId());
        String metaJson = command.metaData() == null || command.metaData().isEmpty()
                ? null : JsonUtil.toJson(command.metaData());
        LocalDateTime expireAt = rule.getExpireDays() == null
                ? null : LocalDateTime.now().plusDays(rule.getExpireDays());

        List<MemoryAddOutcome.AddedNode> added = new ArrayList<>();
        if (hasCustom) {
            MemoryNode node = insertNode(principal.getLibraryId(), rule.getRuleId(), command.userId(),
                    command.customContent().trim(), MemoryNodeSource.CUSTOM, metaJson, expireAt);
            added.add(new MemoryAddOutcome.AddedNode(node, MemoryEventType.ADD));
        }
        if (hasMessages) {
            added.addAll(extractAndApply(principal, command, rule, metaJson, expireAt));
        }
        MemoryProfileService.ProfileView profile = null;
        if (command.profileRuleId() != null && !command.profileRuleId().isBlank()) {
            profile = extractProfile(principal, command);
        }
        log.info("memory add served, libraryId={}, nodeCount={}, profileExtracted={}",
                principal.getLibraryId(), added.size(), profile != null);
        return new MemoryAddOutcome(added, profile);
    }

    /**
     * SearchMemory: recalls the entity's live memories and returns its profiles alongside.
     *
     * @param principal authenticated caller, source of the library
     * @param command   validated call payload
     * @return recall outcome
     */
    public MemorySearchOutcome search(MemoryKeyPrincipal principal, MemorySearchCommand command) {
        List<MemoryProfileService.ProfileView> profiles =
                memoryProfileService.viewsOf(principal.getLibraryId(), command.userId(), null);
        if (command.intentRecognition() && !memoryExtractionService.shouldRecall(command.query())) {
            log.info("memory search vetoed by intent recognition, libraryId={}", principal.getLibraryId());
            return new MemorySearchOutcome(List.of(), profiles, null, false);
        }
        String effectiveQuery = command.rewrite()
                ? memoryExtractionService.rewrite(command.query()) : command.query();
        if (command.fragmentRuleId() != null && !command.fragmentRuleId().isBlank()) {
            requireFragmentRule(principal.getLibraryId(), command.fragmentRuleId());
        }
        int topK = command.rerank()
                ? Math.min(MAX_CANDIDATES, command.maxResults() * RERANK_CANDIDATE_FACTOR)
                : command.maxResults();
        List<MemoryHit> hits = memoryStore.search(MemorySearchQuery.builder()
                .libraryId(principal.getLibraryId())
                .userId(command.userId())
                .queryText(effectiveQuery)
                .embedding(embedOf(effectiveQuery))
                .ruleId(command.fragmentRuleId() == null || command.fragmentRuleId().isBlank()
                        ? null : command.fragmentRuleId())
                .topK(topK)
                .build());
        List<ScoredHit> scored = command.rerank() ? rerank(effectiveQuery, hits, command)
                : hits.stream().map(hit -> new ScoredHit(hit.getNodeId(), hit.getScore())).toList();
        List<MemorySearchOutcome.ScoredNode> nodes =
                hydrate(principal, command.userId(), scored, command.maxResults());
        log.info("memory search served, libraryId={}, hitCount={}, rerank={}",
                principal.getLibraryId(), nodes.size(), command.rerank());
        return new MemorySearchOutcome(nodes, profiles,
                command.rewrite() ? effectiveQuery : null, true);
    }

    /**
     * ListMemory: pages the entity's stored nodes, newest first, expired ones included - listing
     * is management, and management must see what retrieval no longer does.
     *
     * @param principal authenticated caller, source of the library
     * @param userId    memory entity id
     * @param ruleId    restricts to one fragment rule, {@code null} for all
     * @param pageNum   1-based page number
     * @param pageSize  page size
     * @return page of nodes
     */
    public Page<MemoryNode> listNodes(MemoryKeyPrincipal principal, String userId, String ruleId,
                                      int pageNum, int pageSize) {
        return memoryNodeMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MemoryNode>()
                        .eq(MemoryNode::getLibraryId, principal.getLibraryId())
                        .eq(MemoryNode::getUserId, userId)
                        .eq(ruleId != null && !ruleId.isBlank(), MemoryNode::getRuleId, ruleId)
                        .orderByDesc(MemoryNode::getId));
    }

    /**
     * UpdateMemory: replaces a node's content, optionally its metadata, and refreshes the search
     * copy with a new vector.
     *
     * @param principal authenticated caller, source of the library
     * @param nodeId    node business id
     * @param userId    memory entity id the node must belong to
     * @param content   new content
     * @param metaData  new metadata, {@code null} keeps the stored one
     * @return updated row
     */
    public MemoryNode updateNode(MemoryKeyPrincipal principal, String nodeId, String userId,
                                 String content, Map<String, Object> metaData) {
        MemoryNode node = requireNode(principal.getLibraryId(), nodeId, userId);
        node.setContent(content.trim());
        if (metaData != null) {
            node.setMetaData(metaData.isEmpty() ? null : JsonUtil.toJson(metaData));
        }
        memoryNodeMapper.updateById(node);
        index(node);
        log.info("memory node updated, nodeId={}, libraryId={}", nodeId, principal.getLibraryId());
        return node;
    }

    /**
     * DeleteMemory: soft deletes the row and removes the search copy.
     *
     * @param principal authenticated caller, source of the library
     * @param nodeId    node business id
     * @param userId    memory entity id the node must belong to
     */
    public void deleteNode(MemoryKeyPrincipal principal, String nodeId, String userId) {
        MemoryNode node = requireNode(principal.getLibraryId(), nodeId, userId);
        memoryNodeMapper.deleteById(node.getId());
        memoryStore.delete(nodeId);
        log.info("memory node deleted, nodeId={}, libraryId={}", nodeId, principal.getLibraryId());
    }

    /**
     * GetUserProfile: the entity's profile views, initial values filled in for unfilled fields.
     *
     * @param principal authenticated caller, source of the library
     * @param userId    memory entity id
     * @param ruleId    restricts to one profile rule, {@code null} for all
     * @return profile views
     */
    public List<MemoryProfileService.ProfileView> profiles(MemoryKeyPrincipal principal,
                                                           String userId, String ruleId) {
        return memoryProfileService.viewsOf(principal.getLibraryId(), userId, ruleId);
    }

    /**
     * Runs the fragment extraction and applies its commands.
     *
     * @param principal authenticated caller
     * @param command   call payload
     * @param rule      resolved fragment rule
     * @param metaJson  serialised caller metadata
     * @param expireAt  expiry stamp of nodes created by this call
     * @return nodes written or revised
     */
    private List<MemoryAddOutcome.AddedNode> extractAndApply(MemoryKeyPrincipal principal,
                                                             MemoryAddCommand command,
                                                             MemoryFragmentRule rule,
                                                             String metaJson,
                                                             LocalDateTime expireAt) {
        List<MemoryNode> oldMemories = memoryPromptAssembler.updateAllowed(rule)
                ? liveNodesOf(principal.getLibraryId(), rule.getRuleId(), command.userId())
                : List.of();
        Map<String, MemoryNode> oldById = oldMemories.stream()
                .collect(Collectors.toMap(MemoryNode::getNodeId, Function.identity()));
        List<MemoryAddOutcome.AddedNode> added = new ArrayList<>();
        for (MemoryCommand extracted : memoryExtractionService
                .extractFragments(rule, command.messages(), oldMemories)) {
            if (extracted.event() == MemoryEventType.UPDATE) {
                // The parser only lets an UPDATE through when its target is in the window loaded
                // above, so the lookup cannot miss.
                MemoryNode target = oldById.get(extracted.nodeId());
                target.setContent(extracted.content());
                memoryNodeMapper.updateById(target);
                index(target);
                added.add(new MemoryAddOutcome.AddedNode(target, MemoryEventType.UPDATE));
                continue;
            }
            MemoryNode node = insertNode(principal.getLibraryId(), rule.getRuleId(), command.userId(),
                    extracted.content(), MemoryNodeSource.EXTRACTED, metaJson, expireAt);
            added.add(new MemoryAddOutcome.AddedNode(node, MemoryEventType.ADD));
        }
        return added;
    }

    /**
     * Runs the profile extraction of one add call and merges its result.
     *
     * @param principal authenticated caller
     * @param command   call payload
     * @return profile view after the merge
     */
    private MemoryProfileService.ProfileView extractProfile(MemoryKeyPrincipal principal,
                                                            MemoryAddCommand command) {
        MemoryProfileRule profileRule = memoryProfileService
                .requireRule(principal.getLibraryId(), command.profileRuleId());
        Map<String, String> extracted = memoryExtractionService.extractProfile(
                memoryProfileService.fieldsOf(profileRule), command.messages());
        memoryProfileService.merge(profileRule, command.userId(), extracted);
        return memoryProfileService.viewOf(profileRule, command.userId());
    }

    /**
     * Creates one node in MySQL and its search copy.
     *
     * @param libraryId library business id
     * @param ruleId    fragment rule business id
     * @param userId    memory entity id
     * @param content   remembered content
     * @param source    how the node came to exist
     * @param metaJson  serialised caller metadata
     * @param expireAt  expiry stamp, {@code null} for never
     * @return stored row
     */
    private MemoryNode insertNode(String libraryId, String ruleId, String userId, String content,
                                  MemoryNodeSource source, String metaJson, LocalDateTime expireAt) {
        MemoryNode node = new MemoryNode();
        node.setNodeId(bizIdGenerator.memoryNodeId());
        node.setLibraryId(libraryId);
        node.setRuleId(ruleId);
        node.setUserId(userId);
        node.setContent(content);
        node.setSource(source);
        node.setMetaData(metaJson);
        node.setExpireAt(expireAt);
        memoryNodeMapper.insert(node);
        index(node);
        return node;
    }

    /**
     * Writes or refreshes the search copy of one node.
     *
     * @param node stored row
     */
    private void index(MemoryNode node) {
        memoryStore.upsert(MemoryDoc.builder()
                .nodeId(node.getNodeId())
                .libraryId(node.getLibraryId())
                .ruleId(node.getRuleId())
                .userId(node.getUserId())
                .content(node.getContent())
                .embedding(embedOf(node.getContent()))
                .expireAt(node.getExpireAt())
                .build());
    }

    /**
     * Embeds one text, degrading to {@code null} - a BM25-only copy - when no provider is
     * configured or the provider fails. Recall quality drops; the write must not.
     *
     * @param text text to embed
     * @return vector or {@code null}
     */
    private float[] embedOf(String text) {
        if (!embeddingProvider.isConfigured()) {
            return null;
        }
        try {
            List<float[]> vectors = embeddingProvider.embed(List.of(text));
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (Exception e) {
            log.error("memory embedding degraded to bm25, detail follows", e);
            return null;
        }
    }

    /**
     * Reranks the candidates, applying the similarity threshold the contract ties to rerank.
     *
     * <p>Degrades to the recall order when no rerank provider is configured: a missing model must
     * weaken the ordering, never fail the search.
     *
     * @param query   effective query
     * @param hits    recall candidates
     * @param command call payload
     * @return scored hits, best first
     */
    private List<ScoredHit> rerank(String query, List<MemoryHit> hits, MemorySearchCommand command) {
        if (hits.isEmpty()) {
            return List.of();
        }
        if (!rerankProvider.isConfigured()) {
            log.info("memory rerank degraded, reason=provider not configured");
            return hits.stream().map(hit -> new ScoredHit(hit.getNodeId(), hit.getScore())).toList();
        }
        List<Double> scores = rerankProvider.rerank(query,
                hits.stream().map(MemoryHit::getContent).toList());
        List<ScoredHit> scored = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            double score = i < scores.size() && scores.get(i) != null ? scores.get(i) : 0.0;
            if (command.similarityThreshold() != null && score < command.similarityThreshold()) {
                continue;
            }
            scored.add(new ScoredHit(hits.get(i).getNodeId(), score));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }

    /**
     * Re-reads the hits from MySQL, the source of truth, preserving score order.
     *
     * <p>A hit whose row is gone - deleted between the index read and now - is silently skipped;
     * the index catches up on its own writes.
     *
     * @param principal  authenticated caller
     * @param userId     memory entity id
     * @param scored     scored hits, best first
     * @param maxResults most nodes to return
     * @return hydrated nodes with scores
     */
    private List<MemorySearchOutcome.ScoredNode> hydrate(MemoryKeyPrincipal principal, String userId,
                                                         List<ScoredHit> scored, int maxResults) {
        if (scored.isEmpty()) {
            return List.of();
        }
        List<String> nodeIds = scored.stream().map(ScoredHit::nodeId).toList();
        Map<String, MemoryNode> byNodeId = new HashMap<>();
        for (MemoryNode node : memoryNodeMapper.selectList(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, principal.getLibraryId())
                .eq(MemoryNode::getUserId, userId)
                .in(MemoryNode::getNodeId, nodeIds))) {
            byNodeId.put(node.getNodeId(), node);
        }
        List<MemorySearchOutcome.ScoredNode> nodes = new ArrayList<>();
        for (ScoredHit hit : scored) {
            if (nodes.size() >= maxResults) {
                break;
            }
            MemoryNode node = byNodeId.get(hit.nodeId());
            if (node != null) {
                nodes.add(new MemorySearchOutcome.ScoredNode(node, hit.score()));
            }
        }
        return nodes;
    }

    /**
     * Loads the entity's live nodes under one rule, the window a PRO extraction sees.
     *
     * @param libraryId library business id
     * @param ruleId    fragment rule business id
     * @param userId    memory entity id
     * @return newest nodes first, unexpired only, at most {@link #OLD_MEMORY_WINDOW}
     */
    private List<MemoryNode> liveNodesOf(String libraryId, String ruleId, String userId) {
        return memoryNodeMapper.selectList(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId)
                .eq(MemoryNode::getRuleId, ruleId)
                .eq(MemoryNode::getUserId, userId)
                .and(w -> w.isNull(MemoryNode::getExpireAt)
                        .or().gt(MemoryNode::getExpireAt, LocalDateTime.now()))
                .orderByDesc(MemoryNode::getId)
                .last("limit " + OLD_MEMORY_WINDOW));
    }

    /**
     * Resolves the fragment rule an add call runs under.
     *
     * @param libraryId library business id
     * @param ruleId    requested rule, {@code null} or blank takes the library's builtin default
     * @return rule row
     */
    private MemoryFragmentRule resolveFragmentRule(String libraryId, String ruleId) {
        if (ruleId != null && !ruleId.isBlank()) {
            return requireFragmentRule(libraryId, ruleId);
        }
        MemoryFragmentRule builtin = memoryFragmentRuleMapper.selectOne(
                new LambdaQueryWrapper<MemoryFragmentRule>()
                        .eq(MemoryFragmentRule::getLibraryId, libraryId)
                        .eq(MemoryFragmentRule::getBuiltin, 1)
                        .orderByAsc(MemoryFragmentRule::getId)
                        .last("limit 1"));
        if (builtin == null) {
            throw BizException.notFound("记忆库缺少默认片段规则，请指定 fragment_rule_id");
        }
        return builtin;
    }

    /**
     * Loads a fragment rule of one library or fails.
     *
     * @param libraryId library business id, the ownership predicate
     * @param ruleId    fragment rule business id
     * @return rule row
     */
    private MemoryFragmentRule requireFragmentRule(String libraryId, String ruleId) {
        MemoryFragmentRule rule = memoryFragmentRuleMapper.selectOne(
                new LambdaQueryWrapper<MemoryFragmentRule>()
                        .eq(MemoryFragmentRule::getLibraryId, libraryId)
                        .eq(MemoryFragmentRule::getRuleId, ruleId)
                        .last("limit 1"));
        if (rule == null) {
            throw BizException.notFound("fragment rule not found");
        }
        return rule;
    }

    /**
     * Loads a node of one library and one entity or fails with the 404 the contract promises.
     *
     * @param libraryId library business id
     * @param nodeId    node business id
     * @param userId    memory entity id
     * @return node row
     */
    private MemoryNode requireNode(String libraryId, String nodeId, String userId) {
        MemoryNode node = memoryNodeMapper.selectOne(new LambdaQueryWrapper<MemoryNode>()
                .eq(MemoryNode::getLibraryId, libraryId)
                .eq(MemoryNode::getNodeId, nodeId)
                .eq(MemoryNode::getUserId, userId)
                .last("limit 1"));
        if (node == null) {
            throw BizException.notFound("memory node not found");
        }
        return node;
    }

    /**
     * One candidate between recall and hydration.
     *
     * @param nodeId node business id
     * @param score  relevance score
     */
    private record ScoredHit(String nodeId, double score) {
    }
}
