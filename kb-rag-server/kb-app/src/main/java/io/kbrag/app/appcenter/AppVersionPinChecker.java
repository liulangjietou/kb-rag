package io.kbrag.app.appcenter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.mapper.AppVersionMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.port.VersionPinChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Answers the archiving protection question from the frozen version visibility sets of the application
 * versions, requirement sections 4.1 and 4.7.
 *
 * <p><b>A retired version pins too.</b> Its snapshot holds its own copy of the index data, so one might
 * expect archiving the document version to be harmless - but the chunk <em>text</em> lives only in MySQL,
 * which every retrieval reads from after recalling ids. Archiving would leave the snapshot recalling ids
 * whose rows are gone, and a rollback onto that version would return an empty answer while looking healthy.
 * That is exactly the rollback promise section 4.7 makes, so a retired version pins until its snapshot is
 * actually retired by the retention pass.
 *
 * <p><b>The pin is released by clearing the column, not by a status change.</b> The retention pass empties
 * {@code visible_version_ids} when it drops a snapshot, so the pin and the data it protects disappear in the
 * same statement. Keying the answer off the status instead would need the two to be kept in agreement by
 * convention.
 *
 * <p>Soft deleted versions are excluded by the mapper's logical delete filter: a deleted application version
 * serves no traffic and can no longer be rolled back onto.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppVersionPinChecker implements VersionPinChecker {

    private final AppVersionMapper appVersionMapper;
    private final DocumentVersionMapper documentVersionMapper;

    /**
     * {@inheritDoc}
     *
     * <p>A frozen set is keyed by knowledge base and holds document version ids, so there is no way to select
     * the references of one document in SQL; the versions of the document are loaded and the frozen sets are
     * intersected with them. Both sides are small: a version list is bounded by the retention window, and the
     * column is empty for every application version that was never released.
     */
    @Override
    public Map<String, List<String>> pinnedBy(String docId) {
        List<DocumentVersion> documentVersions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, docId));
        if (CollectionUtils.isEmpty(documentVersions)) {
            return Map.of();
        }
        Set<String> ownVersionIds = documentVersions.stream()
                .map(DocumentVersion::getVersionId)
                .collect(Collectors.toSet());
        List<AppVersion> appVersions = appVersionMapper.selectList(new LambdaQueryWrapper<AppVersion>()
                .isNotNull(AppVersion::getVisibleVersionIds));
        if (CollectionUtils.isEmpty(appVersions)) {
            return Map.of();
        }
        Map<String, List<String>> pinnedBy = new LinkedHashMap<>();
        for (AppVersion appVersion : appVersions) {
            for (List<String> frozen : appVersion.visibleVersionIdMap().values()) {
                for (String documentVersionId : frozen) {
                    if (!ownVersionIds.contains(documentVersionId)) {
                        continue;
                    }
                    pinnedBy.computeIfAbsent(documentVersionId, key -> new ArrayList<>())
                            .add(appVersion.getAppVersionId());
                }
            }
        }
        if (!pinnedBy.isEmpty()) {
            log.info("document versions pinned by application versions, docId={}, pinned={}",
                    docId, pinnedBy.keySet());
        }
        return pinnedBy;
    }
}
