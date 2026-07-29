package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.constant.IndexFields;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.ChunkIndexSync;
import io.kbrag.domain.enums.IndexSyncStatus;
import io.kbrag.domain.mapper.ChunkIndexSyncMapper;
import io.kbrag.domain.model.ChunkRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects chunks into the search engines and records what was written where.
 *
 * <p>Extracted from the indexing pipeline because the compensation scan has to replay exactly the
 * same write: if the two built their engine documents independently, a retried chunk could end up
 * with a different field set than the one the pipeline produces, and the difference would only surface
 * as an unexplainable retrieval result much later.
 *
 * <p>The synchronization row is written before the engine call and updated after it. The order is what
 * makes the compensation scan possible at all: a process that dies mid write leaves a PENDING row
 * behind, which is precisely the evidence the scan looks for.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkIndexWriter {

    private static final int WRITE_BATCH_SIZE = 200;
    private static final int ENABLED = 1;

    private final IndexAliasManager indexAliasManager;
    private final IndexSnapshotService indexSnapshotService;
    private final ChunkIndexSyncMapper chunkIndexSyncMapper;

    /**
     * Writes chunks to every target of a knowledge base.
     *
     * @param kbId    knowledge base business id
     * @param chunks  chunks to project, ignored when empty
     * @param vectors embedding per chunk id, empty in zero key mode
     */
    public void write(String kbId, List<Chunk> chunks, Map<String, float[]> vectors) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        for (IndexTarget target : indexAliasManager.ensureIndexes(kbId)) {
            writeTarget(target, chunks, vectors);
        }
    }

    /**
     * Writes chunks to a single target.
     *
     * @param target  write target
     * @param chunks  chunks to project
     * @param vectors embedding per chunk id, only read when the target carries a vector field
     */
    public void writeTarget(IndexTarget target, List<Chunk> chunks, Map<String, float[]> vectors) {
        for (int start = 0; start < chunks.size(); start += WRITE_BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + WRITE_BATCH_SIZE));
            List<ChunkRecord> records = new ArrayList<>(batch.size());
            for (Chunk chunk : batch) {
                registerSync(chunk.getChunkId(), target, IndexSyncStatus.PENDING);
                records.add(toRecord(chunk, target.carriesVector() ? vectors.get(chunk.getChunkId()) : null));
            }
            indexAliasManager.write(target, records);
            for (Chunk chunk : batch) {
                registerSync(chunk.getChunkId(), target, IndexSyncStatus.SYNCED);
            }
        }
        log.info("target written, engine={}, index={}, chunks={}",
                target.engine().code(), target.physicalIndexName(), chunks.size());
    }

    /**
     * Mirrors the retrieval switch of chunks into every target of a knowledge base.
     *
     * <p>Routed through this class rather than straight to the alias manager so that every path which
     * changes what the engines hold for a chunk goes through one collaborator, and so the annotation
     * pipeline never has to know how many targets a deployment has.
     *
     * <p>No synchronization row is touched: the row records whether the chunk reached the index, and a
     * flag update neither creates nor loses a projection.
     *
     * <p><b>The release snapshots are updated too</b>, requirement section 4.5. Disabling is a quality stop,
     * and a released version answering out of a frozen index is serving traffic just as much as the live
     * alias is; a stop that only reached the live index would leave the content readable through the open API.
     * This is the one operation that crosses into a snapshot - content edits deliberately do not, because a
     * snapshot is supposed to hold the text of its release.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to update, ignored when empty
     * @param enabled  new retrieval switch value
     */
    public void syncEnabled(String kbId, List<String> chunkIds, boolean enabled) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        indexAliasManager.updateEnabledEverywhere(kbId, chunkIds, enabled);
        indexSnapshotService.broadcastEnabled(kbId, chunkIds, enabled);
        log.info("chunk enabled flag synced, kbId={}, chunks={}, enabled={}", kbId, chunkIds.size(), enabled);
    }

    /**
     * Builds the engine side projection of a chunk.
     *
     * <p>Only the fixed subset of {@code metadata} declared in {@link ChunkMetadataKeys} crosses over;
     * everything else stays in MySQL. A key that is absent simply leaves its engine field empty, so a
     * document without chat metadata is indexed exactly as before rather than being rejected.
     *
     * <p>The one exception is the operator extracted metadata of the M14 contract section 3.2: every
     * key outside the reserved set is mirrored under the {@code ext_} namespace so the retrieval side
     * can filter on it, while MySQL keeps the raw key name.
     *
     * @param chunk  fact source row
     * @param vector embedding, {@code null} when the target declares no vector field
     * @return engine document
     */
    public ChunkRecord toRecord(Chunk chunk, float[] vector) {
        Map<String, Object> metadata = parseMetadata(chunk.getMetadata());
        return ChunkRecord.builder()
                .chunkId(chunk.getChunkId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .documentVersionId(chunk.getDocumentVersionId())
                .parentId(chunk.getParentId())
                .chunkType(chunk.getChunkType().code())
                .enabled(chunk.getEnabled() != null && chunk.getEnabled() == ENABLED)
                .tagIds(stringList(metadata.get(ChunkMetadataKeys.TAG_IDS)))
                .sessionId(text(metadata.get(ChunkMetadataKeys.SESSION_ID)))
                .sender(text(metadata.get(ChunkMetadataKeys.SENDER)))
                .msgTime(epochMillis(metadata.get(ChunkMetadataKeys.MSG_TIME)))
                .extMetadata(extMetadataOf(metadata))
                .chunkSeq(chunk.getSeq())
                .content(chunk.getContent())
                .vector(vector)
                .build();
    }

    /**
     * Collects the operator extracted keys under the engine side {@code ext_} namespace.
     *
     * <p>Only strings and string lists cross over - those are the two shapes the metadata rules
     * produce, and anything else in the column is a platform structure that has no engine field.
     *
     * @param metadata parsed metadata document
     * @return prefixed extracted values, empty when the chunk carries none
     */
    private Map<String, Object> extMetadataOf(Map<String, Object> metadata) {
        Map<String, Object> ext = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (ChunkMetadataKeys.RESERVED.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof String value && !value.isBlank()) {
                ext.put(IndexFields.EXT_PREFIX + entry.getKey(), value);
            } else if (entry.getValue() instanceof List<?>) {
                List<String> values = stringList(entry.getValue());
                if (!values.isEmpty()) {
                    ext.put(IndexFields.EXT_PREFIX + entry.getKey(), values);
                }
            }
        }
        return ext;
    }

    /**
     * Upserts the synchronization record of one chunk against one physical index.
     *
     * @param chunkId chunk business id
     * @param target  write target
     * @param status  new synchronization state
     */
    public void registerSync(String chunkId, IndexTarget target, IndexSyncStatus status) {
        ChunkIndexSync existing = findSync(chunkId, target.physicalIndexName());
        if (existing == null) {
            ChunkIndexSync record = new ChunkIndexSync();
            record.setChunkId(chunkId);
            record.setPhysicalIndexName(target.physicalIndexName());
            record.setEngine(target.engine().code());
            record.setStatus(status);
            record.setRetryCount(0);
            chunkIndexSyncMapper.insert(record);
            return;
        }
        existing.setStatus(status);
        chunkIndexSyncMapper.updateById(existing);
    }

    /**
     * Records a failed write so the compensation scan picks the chunk up.
     *
     * @param chunkId           chunk business id
     * @param physicalIndexName physical index the write targeted
     * @param retryCount        number of attempts already performed
     */
    public void markFailed(String chunkId, String physicalIndexName, int retryCount) {
        ChunkIndexSync existing = findSync(chunkId, physicalIndexName);
        if (existing == null) {
            return;
        }
        existing.setStatus(IndexSyncStatus.FAILED);
        existing.setRetryCount(retryCount);
        chunkIndexSyncMapper.updateById(existing);
    }

    private ChunkIndexSync findSync(String chunkId, String physicalIndexName) {
        return chunkIndexSyncMapper.selectOne(new LambdaQueryWrapper<ChunkIndexSync>()
                .eq(ChunkIndexSync::getChunkId, chunkId)
                .eq(ChunkIndexSync::getPhysicalIndexName, physicalIndexName)
                .last("limit 1"));
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtil.parse(json, new TypeReference<Map<String, Object>>() {
        });
        return parsed == null ? Map.of() : parsed;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Reads a timestamp that may have been stored as a number or as a numeric string.
     *
     * @param value metadata value
     * @return epoch milliseconds, {@code null} when absent or unparsable
     */
    private Long epochMillis(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                log.info("skip unparsable msg_time metadata, value={}", text);
            }
        }
        return null;
    }
}
