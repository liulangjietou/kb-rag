package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.ImageAsset;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.model.ImageInput;
import io.kbrag.domain.model.IndexSpec;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.MultimodalEmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.IndexNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the multimodal index of a knowledge base, the M14 contract section 6.2.
 *
 * <p>A second, parallel index rather than a field on the text one: the multimodal vectors live in a
 * different embedding space and carry a different dimension, so mixing them into the text index would
 * force a rebuild of the text vectors on every multimodal model switch and vice versa. The multimodal
 * marker in the physical name keeps the two apart while the deployment engine stays the same - a
 * Qdrant collection in full mode, an Elasticsearch dense_vector index in lite mode.
 *
 * <p><b>What gets a vector.</b> Every chunk that carries {@code image_urls} - a standalone upload, a
 * scanned page render or a chunk that inlined an embedded illustration - is embedded from its original
 * image bytes, keyed by chunk id so the multimodal hit is the very same chunk the main index holds and
 * the fusion stage deduplicates on chunk id alone. The vision text proxy path is untouched: the
 * multimodal route is added on top, not swapped in.
 *
 * <p>The switch and the provider gate this together: a disabled switch or a blank credential skips the
 * whole index exactly like a zero key deployment skips embedding, which keeps every collaborator free
 * of null checks. The synchronization rows reuse {@code t_kb_chunk_index_sync} keyed by the physical
 * index name, so the compensation scan needs no change.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultimodalIndexManager {

    private static final int CURRENT = 1;

    private final KbProperties properties;
    private final IndexNaming indexNaming;
    private final MultimodalEmbeddingProvider multimodalEmbeddingProvider;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final IndexRegistryMapper indexRegistryMapper;
    private final ObjectStorage objectStorage;
    private final ImageAssetService imageAssetService;
    private final ChunkIndexWriter chunkIndexWriter;

    /**
     * Produces the multimodal vectors of a version and writes them to the multimodal index.
     *
     * <p>A no-op unless the switch is on and the provider holds a credential, so the indexing pipeline
     * can call it unconditionally. An image that cannot be read is dropped from the batch rather than
     * failing the version, mirroring the "an image never fails a document" rule of the vision stage.
     *
     * @param kbId      knowledge base business id
     * @param versionId document version business id, source of the image media types
     * @param chunks    indexable chunks of the version
     * @param config    knowledge base index configuration
     */
    public void index(String kbId, String versionId, List<Chunk> chunks, KbIndexConfig config) {
        if (config == null || !config.isMultimodalEnabled()) {
            return;
        }
        if (!multimodalEmbeddingProvider.isConfigured()) {
            log.info("multimodal embedding provider not configured, multimodal index skipped, kbId={}", kbId);
            return;
        }
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        Map<String, String> mediaTypes = mediaTypesByKey(versionId);
        List<Chunk> imageChunks = new ArrayList<>();
        List<ImageInput> inputs = new ArrayList<>();
        for (Chunk chunk : chunks) {
            String objectKey = firstImageKey(chunk.getMetadata());
            if (objectKey == null) {
                continue;
            }
            byte[] content = read(objectKey);
            if (content == null || content.length == 0) {
                continue;
            }
            imageChunks.add(chunk);
            inputs.add(new ImageInput(content, mediaTypes.get(objectKey)));
        }
        if (imageChunks.isEmpty()) {
            return;
        }
        Map<String, float[]> vectors = embed(imageChunks, inputs);
        IndexTarget target = ensureIndex(kbId);
        chunkIndexWriter.writeTarget(target, imageChunks, vectors);
        log.info("multimodal index written, kbId={}, index={}, chunks={}",
                kbId, target.physicalIndexName(), imageChunks.size());
    }

    /**
     * Alias the multimodal retrieval route reads through, {@code null} when the capability is off.
     *
     * @param kbId   knowledge base business id
     * @param config knowledge base index configuration
     * @return multimodal alias, or {@code null} when the switch is off or the provider is unconfigured
     */
    public String multimodalAlias(String kbId, KbIndexConfig config) {
        if (config == null || !config.isMultimodalEnabled() || !multimodalEmbeddingProvider.isConfigured()) {
            return null;
        }
        return indexNaming.multimodalAlias(kbId);
    }

    /**
     * Embeds the images of each chunk in provider sized batches, keyed by chunk id.
     *
     * @param imageChunks chunks carrying an image, aligned with {@code inputs}
     * @param inputs      image bytes to embed, one per chunk
     * @return vector per chunk id, in submitted order
     */
    private Map<String, float[]> embed(List<Chunk> imageChunks, List<ImageInput> inputs) {
        int batchSize = Math.max(1, multimodalEmbeddingProvider.maxBatchSize());
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int start = 0; start < inputs.size(); start += batchSize) {
            List<ImageInput> slice = inputs.subList(start, Math.min(inputs.size(), start + batchSize));
            List<float[]> embedded = multimodalEmbeddingProvider.embedImages(slice);
            for (int offset = 0; offset < embedded.size(); offset++) {
                vectors.put(imageChunks.get(start + offset).getChunkId(), embedded.get(offset));
            }
        }
        return vectors;
    }

    /**
     * Creates the multimodal physical index, binds its alias and registers the row. Idempotent.
     *
     * @param kbId knowledge base business id
     * @return the multimodal write target
     */
    private IndexTarget ensureIndex(String kbId) {
        VectorEngine engine = properties.getVector().resolved();
        String embeddingSegment = indexNaming.embeddingSegment(multimodalEmbeddingProvider.model());
        String physicalName = indexNaming.multimodalPhysicalName(kbId, embeddingSegment);
        String alias = indexNaming.multimodalAlias(kbId);
        int dimension = multimodalEmbeddingProvider.dimension();
        IndexSpec spec = IndexSpec.builder()
                .physicalIndexName(physicalName)
                .aliasName(alias)
                .dimension(dimension)
                .schemaVersion(KbConstants.INDEX_SCHEMA_VERSION)
                .build();
        if (engine == VectorEngine.ES) {
            fulltextStore.ensureIndex(spec);
        } else {
            vectorStore.ensureIndex(spec);
        }
        register(kbId, engine, physicalName, alias, embeddingSegment);
        return new IndexTarget(engine, physicalName, alias, embeddingSegment, true, dimension);
    }

    private void register(String kbId, VectorEngine engine, String physicalName, String alias,
                          String embeddingSegment) {
        IndexRegistry existing = indexRegistryMapper.selectOne(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getPhysicalIndexName, physicalName)
                .last("limit 1"));
        if (existing != null) {
            return;
        }
        IndexRegistry registry = new IndexRegistry();
        registry.setKbId(kbId);
        registry.setEngine(engine.code());
        registry.setPhysicalIndexName(physicalName);
        registry.setAliasName(alias);
        registry.setIsCurrent(CURRENT);
        registry.setEmbeddingProvider(multimodalEmbeddingProvider.providerName());
        registry.setEmbeddingModel(multimodalEmbeddingProvider.model());
        registry.setEmbeddingVersion(embeddingSegment);
        registry.setSnapshotVersion(KbConstants.SNAPSHOT_SEGMENT_V1);
        registry.setSchemaVersion(KbConstants.INDEX_SCHEMA_VERSION);
        registry.setStatus(IndexRegistryStatus.ACTIVE);
        indexRegistryMapper.insert(registry);
        log.info("multimodal index registered, kbId={}, engine={}, index={}, alias={}",
                kbId, engine.code(), physicalName, alias);
    }

    /**
     * Maps each stored object key to the media type recorded for it, so the provider request declares
     * the correct MIME type instead of guessing from the bytes.
     *
     * @param versionId document version business id
     * @return media type per object key, empty when the version holds no image
     */
    private Map<String, String> mediaTypesByKey(String versionId) {
        List<ImageAsset> assets = imageAssetService.findByVersion(versionId);
        if (CollectionUtils.isEmpty(assets)) {
            return Map.of();
        }
        Map<String, String> mediaTypes = new LinkedHashMap<>(assets.size());
        for (ImageAsset asset : assets) {
            mediaTypes.put(asset.getObjectKey(), asset.getMediaType());
        }
        return mediaTypes;
    }

    /**
     * Reads the first image object key a chunk carries, {@code null} when it carries none.
     *
     * @param metadataJson raw chunk metadata document
     * @return first image object key, or {@code null}
     */
    private String firstImageKey(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        Map<String, Object> metadata = JsonUtil.parse(metadataJson, new TypeReference<Map<String, Object>>() {
        });
        if (metadata == null || !(metadata.get(ChunkMetadataKeys.IMAGE_URLS) instanceof List<?> keys)
                || keys.isEmpty()) {
            return null;
        }
        Object first = keys.get(0);
        return first == null ? null : String.valueOf(first);
    }

    /**
     * Reads an object's bytes, returning {@code null} on any failure so a single unreadable image is
     * dropped from the multimodal batch instead of failing the version.
     *
     * @param objectKey object storage key
     * @return image bytes, or {@code null} when the object cannot be read
     */
    private byte[] read(String objectKey) {
        try (InputStream in = objectStorage.get(objectKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.error("multimodal image unreadable, errorCode={}, objectKey={}",
                    ErrorCode.INTERNAL_ERROR, objectKey, e);
            return null;
        }
    }
}
