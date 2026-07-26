package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.constant.KbConstants;
import io.kbrag.domain.entity.IndexRegistry;
import io.kbrag.domain.enums.IndexRegistryStatus;
import io.kbrag.domain.enums.VectorEngine;
import io.kbrag.domain.mapper.IndexRegistryMapper;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.port.FulltextStore;
import io.kbrag.domain.port.VectorStore;
import io.kbrag.domain.service.IndexNaming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates, retires and broadcasts to the immutable index snapshots of a release, requirement section 4.7
 * "index snapshot".
 *
 * <p><b>What a snapshot is.</b> One new physical index per engine of a knowledge base, holding the corpus as
 * it stood at release time, registered in {@code t_kb_index_registry} and deliberately <em>not</em> bound to
 * any alias. The live index and its alias are untouched, which is what lets the knowledge base go on being
 * indexed while a released application version keeps answering out of the corpus its release gate measured.
 *
 * <p><b>Why the sequence is knowledge base level.</b> A full mode deployment snapshots two indices per base -
 * the BM25 index and the vector collection - and they belong to the same release. Deriving one {@code sN} per
 * base and using it for both makes that grouping readable from the index names alone, which is what an
 * operator needs when a backup has to be matched to a version by hand.
 *
 * <p><b>Why the sequence comes from the registry.</b> The registry is the only record that survives a process
 * restart and knows about snapshots an application version no longer references. Deriving the next sequence
 * from the application versions instead would reuse a number whose index still exists, and the reuse would be
 * discovered as a name collision at the worst possible moment.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexSnapshotService {

    /** A snapshot is never the target of an alias, so its registry row is not the current one. */
    private static final int NOT_CURRENT = 0;

    /** Sequence of the first snapshot of a knowledge base. */
    private static final int FIRST_SEQUENCE = 1;

    private final IndexAliasManager indexAliasManager;
    private final IndexNaming indexNaming;
    private final FulltextStore fulltextStore;
    private final VectorStore vectorStore;
    private final IndexRegistryMapper indexRegistryMapper;

    /**
     * Snapshots every engine of one knowledge base.
     *
     * <p>Fails on the first engine that cannot be copied, without cleaning up the ones already copied: the
     * caller is releasing several knowledge bases and has to undo all of them together, so a partial cleanup
     * here would only hide from it what there is to undo. It gets the created names back through the
     * exception free path or by holding on to what earlier calls returned.
     *
     * @param kbId knowledge base business id
     * @return one snapshot descriptor per engine, in write order
     */
    public List<AppIndexSnapshot> snapshot(String kbId) {
        int sequence = nextSequence(kbId);
        List<IndexTarget> targets = indexAliasManager.resolveTargets(kbId);
        List<AppIndexSnapshot> created = new ArrayList<>(targets.size());
        for (IndexTarget target : targets) {
            String snapshotName = indexNaming.snapshotPhysicalName(kbId, target.embeddingSegment(), sequence);
            if (target.engine() == VectorEngine.ES) {
                fulltextStore.snapshotIndex(target.physicalIndexName(), snapshotName);
            } else {
                vectorStore.snapshotIndex(target.physicalIndexName(), snapshotName);
            }
            register(kbId, target, snapshotName, indexNaming.snapshotSegment(sequence));
            created.add(new AppIndexSnapshot(kbId, target.engine().code(), snapshotName));
        }
        log.info("index snapshot created, kbId={}, sequence={}, indexes={}",
                kbId, sequence, created.stream().map(AppIndexSnapshot::physicalIndexName).toList());
        return created;
    }

    /**
     * Deletes snapshot indices and marks their registry rows for cleanup.
     *
     * <p>Best effort per snapshot: a name that cannot be deleted is logged and the remaining ones are still
     * attempted. The two callers are a release rolling its own half built snapshots back and the retention
     * pass retiring expired ones, and for both the worst outcome of a failure is an index that costs disk -
     * far cheaper than abandoning the rest of the cleanup.
     *
     * @param snapshots snapshots to drop, ignored when empty
     */
    public void drop(List<AppIndexSnapshot> snapshots) {
        if (CollectionUtils.isEmpty(snapshots)) {
            return;
        }
        for (AppIndexSnapshot snapshot : snapshots) {
            try {
                if (VectorEngine.from(snapshot.engine()) == VectorEngine.ES) {
                    fulltextStore.dropIndex(snapshot.physicalIndexName());
                } else {
                    vectorStore.dropIndex(snapshot.physicalIndexName());
                }
                indexRegistryMapper.update(null, new LambdaUpdateWrapper<IndexRegistry>()
                        .set(IndexRegistry::getStatus, IndexRegistryStatus.PENDING_CLEANUP)
                        .eq(IndexRegistry::getPhysicalIndexName, snapshot.physicalIndexName()));
            } catch (Exception e) {
                log.error("snapshot index could not be dropped, errorCode={}, index={}",
                        ErrorCode.INTERNAL_ERROR, snapshot.physicalIndexName(), e);
            }
        }
        log.info("snapshot indexes dropped, count={}, indexes={}", snapshots.size(),
                snapshots.stream().map(AppIndexSnapshot::physicalIndexName).toList());
    }

    /**
     * Mirrors the retrieval switch of chunks into every live snapshot of a knowledge base, requirement
     * section 4.5 "disable a chunk".
     *
     * <p><b>Why disabling crosses into snapshots at all, when nothing else does.</b> Disabling a chunk is a
     * quality stop: an operator found content that must stop being served, and "served" includes every
     * released version still answering out of a snapshot. Content edits are the opposite case - a snapshot is
     * meant to hold the text of its release, so re-embedding a corrected chunk into it would rewrite history.
     *
     * <p>Best effort: a snapshot whose engine refuses the update is logged and the remaining ones still get
     * it. The live index has already been updated by the caller, and MySQL - which every retrieval reads the
     * text from - already says disabled, so a stale flag in a snapshot can only waste a recall slot.
     *
     * @param kbId     knowledge base business id
     * @param chunkIds chunk ids to update, ignored when empty
     * @param enabled  new retrieval switch value
     */
    public void broadcastEnabled(String kbId, List<String> chunkIds, boolean enabled) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        List<IndexRegistry> snapshots = activeSnapshotsOf(kbId);
        if (CollectionUtils.isEmpty(snapshots)) {
            return;
        }
        for (IndexRegistry snapshot : snapshots) {
            try {
                if (VectorEngine.from(snapshot.getEngine()) == VectorEngine.ES) {
                    fulltextStore.updateEnabled(snapshot.getPhysicalIndexName(), chunkIds, enabled);
                } else {
                    vectorStore.updateEnabled(snapshot.getPhysicalIndexName(), chunkIds, enabled);
                }
            } catch (Exception e) {
                log.error("enabled flag broadcast to a snapshot failed, errorCode={}, index={}",
                        ErrorCode.INTERNAL_ERROR, snapshot.getPhysicalIndexName(), e);
            }
        }
        log.info("enabled flag broadcast to snapshots, kbId={}, snapshots={}, chunks={}, enabled={}",
                kbId, snapshots.size(), chunkIds.size(), enabled);
    }

    /**
     * Registry rows of the snapshots of a knowledge base that are still live.
     *
     * <p>Told apart from a live index by the snapshot segment: the live one is {@code v1} and a snapshot is
     * {@code sN}, so the prefix match is an exact predicate rather than a guess about the alias state.
     *
     * @param kbId knowledge base business id
     * @return registry rows, empty when the base has no snapshot
     */
    public List<IndexRegistry> activeSnapshotsOf(String kbId) {
        return indexRegistryMapper.selectList(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getKbId, kbId)
                .eq(IndexRegistry::getStatus, IndexRegistryStatus.ACTIVE)
                .likeRight(IndexRegistry::getSnapshotVersion, KbConstants.SNAPSHOT_SEGMENT_PREFIX));
    }

    /**
     * Sequence the next snapshot of a knowledge base takes.
     *
     * @param kbId knowledge base business id
     * @return highest sequence recorded plus one, {@code 1} for the first snapshot
     */
    public int nextSequence(String kbId) {
        List<IndexRegistry> rows = indexRegistryMapper.selectList(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getKbId, kbId));
        int highest = 0;
        for (IndexRegistry row : rows) {
            highest = Math.max(highest, indexNaming.snapshotSequenceOf(row.getSnapshotVersion()));
        }
        return highest == 0 ? FIRST_SEQUENCE : highest + 1;
    }

    /**
     * Registers one snapshot index.
     *
     * <p>The schema version and the embedding identity are copied from the source target rather than read from
     * the current provider configuration: a clone reproduces the mapping of the index it came from, so the row
     * has to describe that mapping even after the knowledge base switched embedding model.
     *
     * @param kbId            knowledge base business id
     * @param source          live target the snapshot was taken from
     * @param snapshotName    physical name of the snapshot
     * @param snapshotSegment snapshot segment of that name
     */
    private void register(String kbId, IndexTarget source, String snapshotName, String snapshotSegment) {
        IndexRegistry existing = indexRegistryMapper.selectOne(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getPhysicalIndexName, snapshotName)
                .last("limit 1"));
        if (existing != null) {
            existing.setStatus(IndexRegistryStatus.ACTIVE);
            indexRegistryMapper.updateById(existing);
            return;
        }
        IndexRegistry sourceRow = indexRegistryMapper.selectOne(new LambdaQueryWrapper<IndexRegistry>()
                .eq(IndexRegistry::getPhysicalIndexName, source.physicalIndexName())
                .last("limit 1"));
        IndexRegistry registry = new IndexRegistry();
        registry.setKbId(kbId);
        registry.setEngine(source.engine().code());
        registry.setPhysicalIndexName(snapshotName);
        // The alias column records which alias family the snapshot descends from; it is documentation, not a
        // binding, and is_current says so.
        registry.setAliasName(source.aliasName());
        registry.setIsCurrent(NOT_CURRENT);
        registry.setEmbeddingProvider(sourceRow == null ? null : sourceRow.getEmbeddingProvider());
        registry.setEmbeddingModel(sourceRow == null ? null : sourceRow.getEmbeddingModel());
        registry.setEmbeddingVersion(source.embeddingSegment());
        registry.setSnapshotVersion(snapshotSegment);
        registry.setSchemaVersion(sourceRow == null
                ? KbConstants.INDEX_SCHEMA_VERSION : sourceRow.getSchemaVersion());
        registry.setStatus(IndexRegistryStatus.ACTIVE);
        indexRegistryMapper.insert(registry);
    }
}
