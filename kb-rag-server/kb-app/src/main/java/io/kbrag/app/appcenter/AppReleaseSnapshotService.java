package io.kbrag.app.appcenter;

import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.app.index.IndexSnapshotService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.KbRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Freezes the index snapshots and the version visibility set of a release, requirement section 4.7 "version
 * snapshot step two".
 *
 * <p><b>Where this sits in the release.</b> After the gate produced its verdict and before the state machine
 * moves to {@code RELEASED} - the ordering the requirement states explicitly. Later would publish a version
 * that serves the live index for a moment; earlier would snapshot a corpus the gate had not finished measuring.
 *
 * <p><b>Why the two columns are frozen together.</b> A snapshot index and the version visibility set are two
 * halves of one fact: the index holds chunks of many document versions and the set says which of them the
 * release may see. Freezing the index without the set is what produced the empty-recall-after-rollback defect
 * the requirement calls out - filtering an old snapshot by today's active versions matches nothing.
 *
 * <p><b>All or nothing.</b> A knowledge base whose snapshot fails aborts the whole release and every index
 * already created for that attempt is deleted again. A half snapshotted release would be a version serving one
 * base from a frozen index and another from the live one, which is neither the old behaviour nor the new one
 * and could not be reasoned about after the fact.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppReleaseSnapshotService {

    private final IndexSnapshotService indexSnapshotService;
    private final ActiveVersionResolver activeVersionResolver;

    /**
     * Builds the snapshot of one release.
     *
     * @param version version being released, with its configuration already frozen
     * @param snapshot parsed configuration snapshot of that version
     * @return the two frozen columns
     */
    public ReleaseSnapshot freeze(AppVersion version, AppConfigSnapshot snapshot) {
        List<KbRef> kbRefs = snapshot.getKbRefs();
        if (CollectionUtils.isEmpty(kbRefs)) {
            throw BizException.invalidParam("应用版本未关联知识库，无法建立索引快照");
        }
        List<AppIndexSnapshot> indexSnapshots = new ArrayList<>();
        Map<String, List<String>> visibleVersionIds = new LinkedHashMap<>(kbRefs.size());
        try {
            for (KbRef ref : kbRefs) {
                indexSnapshots.addAll(indexSnapshotService.snapshot(ref.kbId()));
                // Read after the copy, not before: the copy is the point in time being frozen, and a set read
                // beforehand could name a version activated during the copy and therefore only half present.
                visibleVersionIds.put(ref.kbId(), activeVersionResolver.activeVersionIds(ref.kbId()));
            }
        } catch (Exception e) {
            log.error("release index snapshot failed, errorCode={}, appVersionId={}, created={}",
                    ErrorCode.INTERNAL_ERROR, version.getAppVersionId(),
                    indexSnapshots.stream().map(AppIndexSnapshot::physicalIndexName).toList(), e);
            indexSnapshotService.drop(indexSnapshots);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "索引快照建立失败，发布已中止，版本状态未变更，可重试", e);
        }
        log.info("release index snapshot frozen, appVersionId={}, kbCount={}, indexes={}",
                version.getAppVersionId(), kbRefs.size(),
                indexSnapshots.stream().map(AppIndexSnapshot::physicalIndexName).toList());
        return new ReleaseSnapshot(indexSnapshots, visibleVersionIds);
    }

    /**
     * Deletes the indices of a snapshot that never became a release.
     *
     * @param snapshot snapshot to undo, {@code null} ignored
     */
    public void discard(ReleaseSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        indexSnapshotService.drop(snapshot.indexSnapshots());
    }

    /**
     * The two columns one release freezes.
     *
     * @param indexSnapshots    physical indices, one per knowledge base and engine
     * @param visibleVersionIds document version ids per knowledge base id
     */
    public record ReleaseSnapshot(List<AppIndexSnapshot> indexSnapshots,
                                  Map<String, List<String>> visibleVersionIds) {
    }
}
