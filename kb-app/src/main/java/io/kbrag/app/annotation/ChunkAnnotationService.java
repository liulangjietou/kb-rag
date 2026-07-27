package io.kbrag.app.annotation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.index.ChunkEmbedder;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.AnnotationPayloadKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.enums.AnnotationType;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.ParentChildParams;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.TextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The four manual chunk operations, all running the same change pipeline.
 *
 * <p><b>Pipeline order: MySQL, then embedding, then both engines.</b> MySQL is the fact source, so it
 * moves first and the engines follow. New engine documents are written inside the transaction while the
 * removal of superseded ones is deferred to after the commit: writing twice is a duplicate for a few
 * seconds, whereas deleting before a rollback leaves a hole no compensation scan can see, because a
 * restored row still claims to be synchronised.
 *
 * <p><b>Why the semantic validation lives here and not in the controller.</b> Three of the five checks
 * the contract lists - same document, same version, contiguous order - are statements about rows that
 * only the database can answer, and the offset bound needs the stored text. Splitting the gate between
 * the controller and this class would mean two places to keep in step, so the whole gate sits at the
 * entry of each operation and the controller only rejects a missing body.
 *
 * <p><b>Parents are not indexed.</b> Every operation therefore asks whether its target has children
 * before deciding to embed and write: a parent chunk lives in MySQL only, and pushing it into the
 * engines would make the same text match twice and score passages of very different lengths against
 * each other. Merging or splitting a parent re-cuts its children, which is the only way the child level
 * stays a partition of the parent text after its boundaries moved.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkAnnotationService {

    /** Retrieval switch value of an active chunk. */
    private static final int ENABLED = 1;

    /** Retrieval switch value of a chunk excluded from retrieval. */
    private static final int DISABLED = 0;

    /** Separator inserted between the texts a merge concatenates. */
    private static final String MERGE_SEPARATOR = "\n";

    /** Smallest number of chunks a merge accepts. */
    private static final int MIN_MERGE_SIZE = 2;

    /** Separator between the parts of an idempotency discriminator. */
    private static final String DISCRIMINATOR_SEPARATOR = "|";

    private final ChunkMapper chunkMapper;
    private final ChunkTextHasher chunkTextHasher;
    private final ChunkIndexWriter chunkIndexWriter;
    private final ChunkEmbedder chunkEmbedder;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnnotationRecorder annotationRecorder;
    private final BizIdGenerator bizIdGenerator;
    private final TextSplitter textSplitter;

    /**
     * Replaces the text of a chunk in place.
     *
     * @param chunkId chunk business id
     * @param content new text
     * @return updated chunk
     */
    @Transactional(rollbackFor = Exception.class)
    public Chunk edit(String chunkId, String content) {
        Chunk chunk = require(chunkId);
        String newHash = chunkTextHasher.hash(content);
        if (newHash.equals(chunk.getChunkTextHash())) {
            // The normalised text is unchanged, so nothing downstream would differ; re-embedding it
            // would only spend a model call to produce the vector that is already stored.
            log.info("chunk edit leaves the normalised text unchanged, chunkId={}", chunkId);
            return chunk;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AnnotationPayloadKeys.BEFORE_EXCERPT, annotationRecorder.excerpt(chunk.getContent()));
        payload.put(AnnotationPayloadKeys.AFTER_EXCERPT, annotationRecorder.excerpt(content));
        payload.put(AnnotationPayloadKeys.BEFORE_HASH, chunk.getChunkTextHash());

        List<Chunk> children = childrenOf(chunkId);
        boolean indexable = CollectionUtils.isEmpty(children);
        chunk.setContent(content);
        chunk.setChunkTextHash(newHash);
        chunk.setEmbeddingStatus(indexable && chunkEmbedder.isConfigured()
                ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        chunkMapper.updateById(chunk);
        invalidateParentOffsets(chunk, children);

        if (indexable) {
            chunkIndexWriter.write(chunk.getKbId(), List.of(chunk), chunkEmbedder.embed(List.of(chunk)));
        } else {
            log.info("edited chunk is a parent and stays out of the engines, chunkId={}", chunkId);
        }
        annotationRecorder.record(chunk, AnnotationType.EDIT, newHash, payload);
        return chunk;
    }

    /**
     * Flips the retrieval switch of a chunk and of its children.
     *
     * <p>The cascade is symmetric. Disabling a parent has to remove every passage it contains, which the
     * requirement states outright; enabling it again restores them, because the parent switch is the
     * coarse control an operator reaches for and a child that must stay out is disabled individually
     * afterwards. An asymmetric cascade would leave a re-enabled parent permanently unrecallable with no
     * indication why.
     *
     * <p>No text changed, so nothing is embedded: only the engine side flag is mirrored.
     *
     * @param chunkId chunk business id
     * @param enabled new retrieval switch value
     * @return chunk ids whose flag actually changed
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> toggle(String chunkId, boolean enabled) {
        Chunk chunk = require(chunkId);
        List<Chunk> children = childrenOf(chunkId);
        int flag = enabled ? ENABLED : DISABLED;

        List<String> changed = new ArrayList<>();
        List<String> cascaded = new ArrayList<>();
        if (!Objects.equals(chunk.getEnabled(), flag)) {
            chunk.setEnabled(flag);
            chunkMapper.updateById(chunk);
            changed.add(chunk.getChunkId());
        }
        for (Chunk child : children) {
            if (Objects.equals(child.getEnabled(), flag)) {
                continue;
            }
            child.setEnabled(flag);
            chunkMapper.updateById(child);
            changed.add(child.getChunkId());
            cascaded.add(child.getChunkId());
        }
        if (CollectionUtils.isNotEmpty(changed)) {
            chunkIndexWriter.syncEnabled(chunk.getKbId(), changed, enabled);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AnnotationPayloadKeys.ENABLED, enabled);
        payload.put(AnnotationPayloadKeys.CASCADED_CHILD_IDS, cascaded);
        payload.put(AnnotationPayloadKeys.EXCERPT, annotationRecorder.excerpt(chunk.getContent()));
        annotationRecorder.record(chunk, AnnotationType.TOGGLE,
                chunk.getChunkTextHash() + DISCRIMINATOR_SEPARATOR + enabled, payload);
        log.info("chunk retrieval switch changed, chunkId={}, enabled={}, changed={}, cascadedChildren={}",
                chunkId, enabled, changed.size(), cascaded.size());
        return changed;
    }

    /**
     * Replaces several adjacent chunks by their concatenation.
     *
     * @param chunkIds chunks to merge, at least two
     * @return chunk the merge produced
     */
    @Transactional(rollbackFor = Exception.class)
    public Chunk merge(List<String> chunkIds) {
        List<Chunk> sources = requireMergeable(chunkIds);
        Chunk first = sources.get(0);
        List<Chunk> children = childrenOfAll(chunkIds);
        boolean parentLevel = CollectionUtils.isNotEmpty(children);

        String content = sources.stream().map(Chunk::getContent).reduce((a, b) -> a + MERGE_SEPARATOR + b)
                .orElse("");
        Chunk merged = new Chunk();
        merged.setChunkId(bizIdGenerator.chunkId());
        merged.setKbId(first.getKbId());
        merged.setDocId(first.getDocId());
        merged.setDocumentVersionId(first.getDocumentVersionId());
        merged.setContent(content);
        merged.setChunkTextHash(chunkTextHasher.hash(content));
        merged.setParentId(first.getParentId());
        // No parent offset: the concatenation inserts a separator between the sources, so the result is not
        // a slice of the parent text any more and no pair of offsets could describe it honestly.
        merged.setSeq(first.getSeq());
        merged.setChunkType(first.getChunkType());
        // Enabled when at least one source was: a merge is a restructuring, and silently disabling the
        // combined passage because one part of it was excluded would remove knowledge nobody removed.
        merged.setEnabled(sources.stream().anyMatch(source -> Objects.equals(source.getEnabled(), ENABLED))
                ? ENABLED : DISABLED);
        merged.setMetadata(first.getMetadata());
        boolean embeddable = !parentLevel && chunkEmbedder.isConfigured();
        merged.setEmbeddingStatus(embeddable ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        chunkMapper.insert(merged);

        int recutChildren = 0;
        if (parentLevel) {
            recutChildren = recutChildren(merged, children);
        } else {
            chunkIndexWriter.write(merged.getKbId(), List.of(merged), chunkEmbedder.embed(List.of(merged)));
        }
        removeChunks(first.getKbId(), chunkIds);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AnnotationPayloadKeys.SOURCE_CHUNK_IDS, chunkIds);
        payload.put(AnnotationPayloadKeys.RESULT_CHUNK_IDS, List.of(merged.getChunkId()));
        payload.put(AnnotationPayloadKeys.EXCERPT, annotationRecorder.excerpt(content));
        payload.put(AnnotationPayloadKeys.RECUT_CHILD_COUNT, recutChildren);
        annotationRecorder.record(merged, first.getChunkId(), AnnotationType.MERGE,
                merged.getChunkTextHash(), payload);
        log.info("chunks merged, docId={}, versionId={}, sources={}, mergedChunkId={}, recutChildren={}",
                first.getDocId(), first.getDocumentVersionId(), chunkIds.size(), merged.getChunkId(),
                recutChildren);
        return merged;
    }

    /**
     * Replaces one chunk by the parts its text is cut into.
     *
     * @param chunkId chunk business id
     * @param offsets character offsets to cut at, strictly ascending and inside the text
     * @return chunks the split produced, in order
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Chunk> split(String chunkId, List<Integer> offsets) {
        Chunk source = require(chunkId);
        requireSplittable(source, offsets);
        List<Chunk> children = childrenOf(chunkId);
        boolean parentLevel = CollectionUtils.isNotEmpty(children);

        List<String> parts = cut(source.getContent(), offsets);
        // The order column is an integer, so the parts cannot be numbered between the neighbours of the
        // source; the tail of the version is shifted instead, which keeps the ordering total and stable.
        shiftFollowingSeq(source, parts.size() - 1);

        List<Chunk> produced = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            Chunk part = new Chunk();
            part.setChunkId(bizIdGenerator.chunkId());
            part.setKbId(source.getKbId());
            part.setDocId(source.getDocId());
            part.setDocumentVersionId(source.getDocumentVersionId());
            part.setContent(parts.get(index));
            part.setChunkTextHash(chunkTextHasher.hash(parts.get(index)));
            part.setParentId(source.getParentId());
            // No parent offset either: a part is a slice of the source chunk rather than of the parent, and
            // the requirement puts every row a merge or a split produces outside the precise redaction.
            part.setSeq(source.getSeq() + index);
            part.setChunkType(source.getChunkType());
            part.setEnabled(Objects.equals(source.getEnabled(), DISABLED) ? DISABLED : ENABLED);
            part.setMetadata(source.getMetadata());
            part.setEmbeddingStatus(!parentLevel && chunkEmbedder.isConfigured()
                    ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
            chunkMapper.insert(part);
            produced.add(part);
        }

        int recutChildren = 0;
        if (parentLevel) {
            for (Chunk part : produced) {
                recutChildren += recutChildren(part, List.of());
            }
            removeChunks(source.getKbId(), children.stream().map(Chunk::getChunkId).toList());
        } else {
            chunkIndexWriter.write(source.getKbId(), produced, chunkEmbedder.embed(produced));
        }
        removeChunks(source.getKbId(), List.of(chunkId));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(AnnotationPayloadKeys.SPLIT_OFFSETS, offsets);
        payload.put(AnnotationPayloadKeys.RESULT_CHUNK_IDS,
                produced.stream().map(Chunk::getChunkId).toList());
        payload.put(AnnotationPayloadKeys.EXCERPT, annotationRecorder.excerpt(source.getContent()));
        payload.put(AnnotationPayloadKeys.RECUT_CHILD_COUNT, recutChildren);
        annotationRecorder.record(source, AnnotationType.SPLIT,
                source.getChunkTextHash() + DISCRIMINATOR_SEPARATOR + offsets, payload);
        log.info("chunk split, docId={}, versionId={}, chunkId={}, parts={}, recutChildren={}",
                source.getDocId(), source.getDocumentVersionId(), chunkId, produced.size(), recutChildren);
        return produced;
    }

    /**
     * The single point where a stored child offset is invalidated, requirement section 4.5.
     *
     * <p>Two cases, both of them "the text these offsets address has moved". Editing a <b>parent</b>
     * replaces the very string the offsets are measured in, so every child of it loses its position at
     * once - keeping any of them would cut a passage out of text that no longer contains it. Editing a
     * <b>child</b> replaces the text the offsets are supposed to delimit, so that one child loses its
     * position. The third case the requirement lists - the rows a merge or a split produces - needs no code
     * here: those rows are inserted fresh and a fresh row carries no offset, which is the same statement
     * expressed by construction rather than by an update.
     *
     * <p>Written through an update wrapper rather than through the entity, because {@code updateById}
     * skips {@code null} fields by design and would leave the stale pair in place.
     *
     * @param chunk    chunk whose text was just replaced
     * @param children children of that chunk, empty when it is not a parent
     */
    private void invalidateParentOffsets(Chunk chunk, List<Chunk> children) {
        LambdaUpdateWrapper<Chunk> wrapper = new LambdaUpdateWrapper<Chunk>()
                .set(Chunk::getParentStartOffset, null)
                .set(Chunk::getParentEndOffset, null);
        if (CollectionUtils.isNotEmpty(children)) {
            chunkMapper.update(null, wrapper.eq(Chunk::getParentId, chunk.getChunkId()));
            log.info("child offsets invalidated because the parent text changed, chunkId={}, children={}",
                    chunk.getChunkId(), children.size());
            return;
        }
        if (chunk.getParentId() == null) {
            return;
        }
        chunkMapper.update(null, wrapper.eq(Chunk::getChunkId, chunk.getChunkId()));
        log.info("child offset invalidated because the child text changed, chunkId={}", chunk.getChunkId());
    }

    /**
     * Cuts the children of a parent again and indexes them.
     *
     * <p>Called whenever a parent boundary moves. Re-cutting rather than re-pointing the existing
     * children is what the requirement asks for and what keeps the child level a partition of the parent
     * text produced by the configured child budget; children inherited from two different parents would
     * otherwise carry the lengths of the configuration in force when their old parent was built.
     *
     * @param parent      parent whose text is cut
     * @param obsolete    children of the previous generation, removed by the caller when non empty
     * @return number of children created
     */
    private int recutChildren(Chunk parent, List<Chunk> obsolete) {
        ParentChildParams params = knowledgeBaseService.indexConfigOf(parent.getKbId())
                .parentChildOrDisabled();
        List<SplitChunk> parts = textSplitter.split(parent.getContent(),
                SplitParams.of(params.getChildMaxTokens(), params.getChildOverlap()));
        List<Chunk> created = new ArrayList<>(parts.size());
        for (SplitChunk part : parts) {
            Chunk child = new Chunk();
            child.setChunkId(bizIdGenerator.chunkId());
            child.setKbId(parent.getKbId());
            child.setDocId(parent.getDocId());
            child.setDocumentVersionId(parent.getDocumentVersionId());
            child.setContent(part.getContent());
            child.setChunkTextHash(chunkTextHasher.hash(part.getContent()));
            child.setParentId(parent.getChunkId());
            child.setSeq(part.getSeq());
            child.setChunkType(parent.getChunkType());
            child.setEnabled(Objects.equals(parent.getEnabled(), DISABLED) ? DISABLED : ENABLED);
            child.setEmbeddingStatus(chunkEmbedder.isConfigured()
                    ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
            chunkMapper.insert(child);
            created.add(child);
        }
        if (CollectionUtils.isNotEmpty(created)) {
            chunkIndexWriter.write(parent.getKbId(), created, chunkEmbedder.embed(created));
        }
        if (CollectionUtils.isNotEmpty(obsolete)) {
            removeChunks(parent.getKbId(), obsolete.stream().map(Chunk::getChunkId).toList());
        }
        return created.size();
    }

    /**
     * Loads and validates the sources of a merge.
     *
     * @param chunkIds requested chunks
     * @return sources ordered by their position in the version
     */
    private List<Chunk> requireMergeable(List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds) || chunkIds.size() < MIN_MERGE_SIZE) {
            throw BizException.invalidParam("a merge needs at least " + MIN_MERGE_SIZE + " chunks");
        }
        Set<String> unique = new LinkedHashSet<>(chunkIds);
        if (unique.size() != chunkIds.size()) {
            throw BizException.invalidParam("chunk_ids must not repeat a chunk");
        }
        List<Chunk> sources = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds));
        if (sources.size() != chunkIds.size()) {
            throw BizException.notFound("some chunks do not exist");
        }
        sources = new ArrayList<>(sources);
        sources.sort(Comparator.comparingInt(Chunk::getSeq).thenComparing(Chunk::getChunkId));
        Chunk first = sources.get(0);
        for (int index = 0; index < sources.size(); index++) {
            Chunk current = sources.get(index);
            if (!Objects.equals(current.getDocId(), first.getDocId())
                    || !Objects.equals(current.getDocumentVersionId(), first.getDocumentVersionId())) {
                throw BizException.invalidParam("chunks of a merge must belong to one document version");
            }
            if (!Objects.equals(current.getParentId(), first.getParentId())) {
                throw BizException.invalidParam("chunks of a merge must share the same parent");
            }
            if (index > 0 && current.getSeq() != sources.get(index - 1).getSeq() + 1) {
                throw BizException.invalidParam("chunks of a merge must be consecutive");
            }
        }
        return sources;
    }

    /**
     * Validates the offsets of a split.
     *
     * @param source  chunk being split
     * @param offsets requested offsets
     */
    private void requireSplittable(Chunk source, List<Integer> offsets) {
        if (CollectionUtils.isEmpty(offsets)) {
            throw BizException.invalidParam("split_offsets must not be empty");
        }
        int length = source.getContent() == null ? 0 : source.getContent().length();
        int previous = 0;
        for (Integer offset : offsets) {
            if (offset == null || offset <= previous || offset >= length) {
                throw BizException.invalidParam("split_offsets must be ascending and inside the chunk text");
            }
            previous = offset;
        }
    }

    /**
     * Cuts a text at the given offsets.
     *
     * @param content chunk text
     * @param offsets validated offsets
     * @return parts in order, one more than there are offsets
     */
    private List<String> cut(String content, List<Integer> offsets) {
        List<String> parts = new ArrayList<>(offsets.size() + 1);
        int start = 0;
        for (Integer offset : offsets) {
            parts.add(content.substring(start, offset));
            start = offset;
        }
        parts.add(content.substring(start));
        return parts;
    }

    /**
     * Makes room for the parts of a split by pushing the following chunks of the same level back.
     *
     * @param source chunk being split
     * @param delta  number of extra positions needed
     */
    private void shiftFollowingSeq(Chunk source, int delta) {
        if (delta <= 0) {
            return;
        }
        LambdaUpdateWrapper<Chunk> wrapper = new LambdaUpdateWrapper<Chunk>()
                .setSql("seq = seq + " + delta)
                .eq(Chunk::getDocumentVersionId, source.getDocumentVersionId())
                .gt(Chunk::getSeq, source.getSeq());
        if (source.getParentId() == null) {
            wrapper.isNull(Chunk::getParentId);
        } else {
            wrapper.eq(Chunk::getParentId, source.getParentId());
        }
        chunkMapper.update(null, wrapper);
    }

    private void removeChunks(String kbId, List<String> chunkIds) {
        knowledgeBaseService.removeChunks(kbId, new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getChunkId, chunkIds));
    }

    private List<Chunk> childrenOf(String chunkId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getParentId, chunkId)
                .orderByAsc(Chunk::getSeq));
    }

    private List<Chunk> childrenOfAll(List<String> chunkIds) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .in(Chunk::getParentId, chunkIds)
                .orderByAsc(Chunk::getSeq));
    }

    private Chunk require(String chunkId) {
        Chunk chunk = chunkMapper.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getChunkId, chunkId)
                .last("limit 1"));
        if (chunk == null) {
            throw BizException.notFound("chunk not found");
        }
        return chunk;
    }
}
