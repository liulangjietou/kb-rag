package io.kbrag.app.index;

import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.PageRange;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.ProxiedContent;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.ImageChunkLinker;
import io.kbrag.domain.service.MetadataRuleExtractor;
import io.kbrag.domain.service.PageSplitter;
import io.kbrag.domain.service.ParentChildSplitter;
import io.kbrag.domain.service.SplitterRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuts one staged document version into chunk rows.
 *
 * <p>Split out of {@link IndexPipelineService} along the collaborators: the four splitters, the text
 * hasher, the metadata rule extractor and the image linker are read here and nowhere else in the
 * pipeline. What the pipeline keeps is the decision of <em>when</em> to cut and what to do afterwards -
 * stamping the version fingerprint, moving the task progress, driving the engines; what this class owns
 * is the cut itself and the rows it produces.
 *
 * <p>The strategy questions are answered by the caller and arrive as {@link SplitRequest} flags rather
 * than being re-derived here. A splitter that could ask "is this a standalone image" would be able to
 * disagree with the pipeline stage that already decided it, and the two answers drive different
 * branches - which chunk type is written, and whether the page route runs at all.
 *
 * <p>Both levels are persisted, only the indexable ones are returned: a parent is stored so the
 * retrieval side can read its text out of MySQL, but it is never embedded and never reaches an engine.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkSplitter {

    /** Chunks are born enabled; an operator disables them afterwards. */
    private static final int ENABLED = 1;

    private final SplitterRouter splitterRouter;
    private final ParentChildSplitter parentChildSplitter;
    private final PageSplitter pageSplitter;
    private final ChunkTextHasher chunkTextHasher;
    private final MetadataRuleExtractor metadataRuleExtractor;
    private final ImageChunkLinker imageChunkLinker;
    private final BizIdGenerator bizIdGenerator;
    private final ChunkMapper chunkMapper;

    /**
     * One version's text together with every strategy question the pipeline already settled.
     *
     * @param document            document record
     * @param version             version being built
     * @param proxied             cleaned text and the image placements inside it
     * @param pageRanges          page boundaries inside that text, empty for every strategy but {@code page}
     * @param config              knowledge base index configuration
     * @param embeddingConfigured {@code true} when the vector route is available
     * @param standaloneImage     {@code true} when the document is one uploaded image
     * @param pageStrategy        {@code true} when the page strategy applies to this document
     */
    public record SplitRequest(Document document, DocumentVersion version, ProxiedContent proxied,
                               List<PageRange> pageRanges, KbIndexConfig config,
                               boolean embeddingConfigured, boolean standaloneImage,
                               boolean pageStrategy) {
    }

    /**
     * Cuts the text according to the knowledge base configuration and persists the chunks.
     *
     * <p>A standalone image never takes the two level route: the document is a single picture whose whole
     * text came from the vision model, so there is no structure for a parent to group.
     *
     * @param request text to cut and the strategy flags it is cut under
     * @return chunks that have to reach the engines, parents excluded
     */
    public List<Chunk> split(SplitRequest request) {
        return request.config().parentChildEnabled() && !request.standaloneImage()
                ? splitTwoLevel(request)
                : splitSingleLevel(request);
    }

    /**
     * Cuts the text into a single level and links each chunk to the images it contains.
     *
     * @param request text to cut and the strategy flags it is cut under
     * @return persisted chunks
     */
    private List<Chunk> splitSingleLevel(SplitRequest request) {
        KbIndexConfig config = request.config();
        ProxiedContent proxied = request.proxied();
        SplitParams params = config.splitParams(cacheContextOf(request.document(), request.version()));
        List<SplitChunk> splitChunks = request.pageStrategy()
                ? pageSplitter.split(proxied.getMarkdown(), request.pageRanges(), params)
                : splitterRouter.resolve(config.getSplitStrategy()).split(proxied.getMarkdown(), params);
        Map<Integer, List<String>> imagesByChunk = imageChunkLinker.link(proxied.getMarkdown(),
                proxied.getPlacements(), splitChunks.stream().map(SplitChunk::getContent).toList());
        List<MetadataRuleExtractor.PreparedRule> rules =
                metadataRuleExtractor.prepare(config.metadataRulesOrEmpty());
        List<Chunk> chunks = new ArrayList<>(splitChunks.size());
        for (int index = 0; index < splitChunks.size(); index++) {
            List<String> imageKeys = imagesByChunk.get(index);
            SplitChunk splitChunk = splitChunks.get(index);
            Chunk chunk = persistChunk(request, splitChunk, null, request.embeddingConfigured(),
                    request.standaloneImage() ? ChunkType.IMAGE : ChunkType.TEXT,
                    metadataOf(imageKeys, splitChunk,
                            metadataRuleExtractor.extract(rules, splitChunk.getContent())));
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * Persists both levels and returns only the children.
     *
     * <p>Parents are stored with {@code embedding_status=SKIPPED} because they are never embedded and
     * never written to an engine; marking them PENDING would make the compensation scan chase a write
     * that is not supposed to happen.
     *
     * @param request text to cut and the strategy flags it is cut under
     * @return child chunks
     */
    private List<Chunk> splitTwoLevel(SplitRequest request) {
        KbIndexConfig config = request.config();
        ProxiedContent proxied = request.proxied();
        List<ParentChunk> groups = parentChildSplitter.split(proxied.getMarkdown(),
                config.parentChildOrDisabled());
        List<String> childContents = new ArrayList<>();
        for (ParentChunk group : groups) {
            for (SplitChunk child : group.getChildren()) {
                childContents.add(child.getContent());
            }
        }
        Map<Integer, List<String>> imagesByChunk = imageChunkLinker.link(proxied.getMarkdown(),
                proxied.getPlacements(), childContents);
        List<MetadataRuleExtractor.PreparedRule> rules =
                metadataRuleExtractor.prepare(config.metadataRulesOrEmpty());

        List<Chunk> children = new ArrayList<>();
        int childIndex = 0;
        for (ParentChunk group : groups) {
            Chunk parent = persistChunk(request, group.getParent(), null, false, ChunkType.TEXT, null);
            for (SplitChunk child : group.getChildren()) {
                children.add(persistChunk(request, child, parent.getChunkId(),
                        request.embeddingConfigured(), ChunkType.TEXT,
                        metadataOf(imagesByChunk.get(childIndex++), child,
                                metadataRuleExtractor.extract(rules, child.getContent()))));
            }
        }
        return children;
    }

    /**
     * Merges the image links a chunk carries with the title/summary/keywords the LLM semantic
     * splitter attaches to it, requirement section 4.3, and with the operator extracted metadata of
     * the M14 contract section 3.2.
     *
     * <p>The extracted values go in first so a reserved key wins any collision: the platform written
     * semantics of {@code title} or {@code image_urls} must never be silently replaced by a rule an
     * operator happened to name the same way.
     *
     * @param imageKeys  object storage keys of the images this chunk contains, may be empty
     * @param splitChunk splitter output, {@code null} metadata for every strategy but the LLM one
     * @param extracted  metadata rule output for this chunk, may be empty
     * @return JSON metadata document, {@code null} when there is nothing to store
     */
    private String metadataOf(List<String> imageKeys, SplitChunk splitChunk, Map<String, Object> extracted) {
        Map<String, Object> metadata = new LinkedHashMap<>(extracted);
        if (CollectionUtils.isNotEmpty(imageKeys)) {
            metadata.put(ChunkMetadataKeys.IMAGE_URLS, imageKeys);
        }
        if (splitChunk != null && splitChunk.getMetadata() != null && !splitChunk.getMetadata().isEmpty()) {
            metadata.putAll(splitChunk.getMetadata());
        }
        return metadata.isEmpty() ? null : JsonUtil.toJson(metadata);
    }

    /**
     * Builds the LLM semantic splitter's cache coordinates for one document version.
     *
     * @param document document record
     * @param version  version being built
     * @return cache context, safe to pass to every strategy since only the LLM one reads it
     */
    private SplitParams.CacheContext cacheContextOf(Document document, DocumentVersion version) {
        return new SplitParams.CacheContext(document.getKbId(), document.getDocId(),
                version.getVersionId(), version.getContentHash());
    }

    private Chunk persistChunk(SplitRequest request, SplitChunk splitChunk, String parentId,
                               boolean embeddingConfigured, ChunkType chunkType, String metadata) {
        Document document = request.document();
        Chunk chunk = new Chunk();
        chunk.setChunkId(bizIdGenerator.chunkId());
        chunk.setKbId(document.getKbId());
        chunk.setDocId(document.getDocId());
        chunk.setDocumentVersionId(request.version().getVersionId());
        chunk.setContent(splitChunk.getContent());
        chunk.setChunkTextHash(chunkTextHasher.hash(splitChunk.getContent()));
        chunk.setParentId(parentId);
        if (parentId != null) {
            // Only a child has a position worth storing: the offsets of a single level chunk are relative
            // to the whole document, and storing them in a column the retrieval side reads as "inside my
            // parent" would make it cut a passage out of the wrong text.
            chunk.setParentStartOffset(splitChunk.getStartOffset());
            chunk.setParentEndOffset(splitChunk.getEndOffset());
        }
        chunk.setSeq(splitChunk.getSeq());
        chunk.setChunkType(chunkType);
        chunk.setEnabled(ENABLED);
        chunk.setMetadata(metadata);
        chunk.setEmbeddingStatus(embeddingConfigured ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        chunkMapper.insert(chunk);
        return chunk;
    }
}
