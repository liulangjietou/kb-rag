package io.kbrag.app.appcenter;

import io.kbrag.app.auth.AccessGuard;
import io.kbrag.app.auth.KbResourceGuard;
import io.kbrag.app.document.DocumentAclService;
import io.kbrag.app.index.ActiveVersionResolver;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.mapper.AppCorpusDocumentMapper;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppCorpusDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 编排只读版本语料比较；不冻结索引，不触发评测，也不改变发布状态。 */
@Service
@RequiredArgsConstructor
public class AppVersionComparisonService {
    private static final int READ_BATCH_SIZE = 500;
    private final AppVersionGuard versions;
    private final KbResourceGuard kbGuard;
    private final ActiveVersionResolver activeVersions;
    private final DocumentAclService documentAcl;
    private final AppCorpusDocumentMapper documents;

    /** 先验证两个版本的租户与同应用归属，再按当前授权裁剪资料，最后统计和分页。 */
    @Transactional(readOnly = true)
    public Comparison compareCorpus(String versionId, String baselineId, int page, int pageSize) {
        AppVersion candidate = versions.require(versionId);
        AppVersion baseline = baselineId == null ? null : versions.require(baselineId);
        if (baseline != null && !Objects.equals(candidate.getAppId(), baseline.getAppId())) {
            throw new BizException(ErrorCode.VERSION_NOT_FOUND, "application version not found");
        }
        var user = AccessGuard.currentUserOrNull();
        if (user == null || !user.hasPermission(PermissionCodes.APP_READ)
                || !user.hasPermission(PermissionCodes.KB_READ) || !user.canAccessApp(candidate.getAppId())) {
            throw BizException.forbidden("application corpus comparison is outside current scope");
        }
        Set<String> roots = new LinkedHashSet<>(kbIds(candidate));
        roots.addAll(kbIds(baseline));
        roots.forEach(kbGuard::requireKb);
        Corpus left = readCorpus(baseline, user.tenantId());
        Corpus right = readCorpus(candidate, user.tenantId());
        if (!left.complete || !right.complete) {
            return new Comparison(left.summary(), right.summary(), false, null, null, null, null, page, pageSize, List.of());
        }
        List<DocumentChange> changes = changes(left.documents, right.documents);
        long added = changes.stream().filter(row -> row.change == Change.ADDED).count();
        long removed = changes.stream().filter(row -> row.change == Change.REMOVED).count();
        int from = (int) Math.min((long) (page - 1) * pageSize, changes.size());
        int to = (int) Math.min((long) from + pageSize, changes.size());
        return new Comparison(left.summary(), right.summary(), true, (long) changes.size(), added, removed,
                changes.size() - added - removed, page, pageSize, List.copyOf(changes.subList(from, to)));
    }

    private Corpus readCorpus(AppVersion version, String tenantId) {
        if (version == null) return new Corpus(Source.EMPTY, true, Map.of());
        List<String> kbIds = kbIds(version);
        Map<String, List<String>> frozen = version.visibleVersionIdMap();
        boolean previouslyReleased = version.getReleasedAt() != null || version.getStatus() == AppVersionStatus.RELEASED
                || version.getStatus() == AppVersionStatus.SUPERSEDED;
        boolean hasFrozen = !frozen.isEmpty();
        if ((previouslyReleased || hasFrozen) && kbIds.stream().anyMatch(id -> frozen.get(id) == null)) {
            return new Corpus(Source.UNAVAILABLE, false, Map.of());
        }
        Source source = hasFrozen ? Source.FROZEN : previouslyReleased ? Source.UNAVAILABLE : Source.CURRENT;
        if (source == Source.UNAVAILABLE) return new Corpus(source, false, Map.of());
        Map<DocumentKey, AppCorpusDocument> rows = new LinkedHashMap<>();
        boolean complete = true;
        for (String kbId : kbIds) {
            List<String> ids = hasFrozen ? frozen.get(kbId) : activeVersions.activeVersionIds(kbId);
            // 发布固化资料版本，不固化访问权限；不能让隐藏资料影响差异数和分页。
            List<String> visible = new ArrayList<>(new LinkedHashSet<>(documentAcl.trimRestricted(kbId, ids)));
            for (int offset = 0; offset < visible.size(); offset += READ_BATCH_SIZE) {
                List<String> batch = visible.subList(offset, Math.min(offset + READ_BATCH_SIZE, visible.size()));
                List<AppCorpusDocument> found = documents.selectVersions(tenantId, kbId, batch);
                if (found.size() != batch.size()) complete = false;
                for (AppCorpusDocument row : found) {
                    if (rows.putIfAbsent(new DocumentKey(kbId, row.docId()), row) != null) complete = false;
                }
            }
        }
        return new Corpus(source, complete, rows);
    }

    private List<String> kbIds(AppVersion version) {
        return version == null ? List.of() : JsonUtil.parse(version.getConfig(), AppConfigSnapshot.class).kbIds();
    }

    private List<DocumentChange> changes(Map<DocumentKey, AppCorpusDocument> left, Map<DocumentKey, AppCorpusDocument> right) {
        Set<DocumentKey> keys = new TreeSet<>(java.util.Comparator.comparing(DocumentKey::kbId).thenComparing(DocumentKey::docId));
        keys.addAll(left.keySet());
        keys.addAll(right.keySet());
        List<DocumentChange> changes = new ArrayList<>();
        for (DocumentKey key : keys) {
            AppCorpusDocument before = left.get(key);
            AppCorpusDocument after = right.get(key);
            if (before != null && after != null && before.versionId().equals(after.versionId())) continue;
            Change change = before == null ? Change.ADDED : after == null ? Change.REMOVED : Change.UPDATED;
            changes.add(new DocumentChange(key.kbId, key.docId, after == null ? before.fileName() : after.fileName(),
                    change, before, after));
        }
        return changes;
    }

    private record DocumentKey(String kbId, String docId) { }
    private record Corpus(Source source, boolean complete, Map<DocumentKey, AppCorpusDocument> documents) {
        CorpusSummary summary() { return new CorpusSummary(source, complete, complete ? documents.size() : null); }
    }
    /** 资料集合的来源；历史缺失与合法空集分别表达。 */
    public enum Source { EMPTY, FROZEN, CURRENT, UNAVAILABLE }
    /** 相对对照集合的文档变化。 */
    public enum Change { ADDED, REMOVED, UPDATED }
    /** 已按当前授权裁剪的资料集合概况，不完整时数量未知。 */
    public record CorpusSummary(Source source, boolean complete, Integer documentCount) { }
    /** 同一知识库内一个文档的版本变化。 */
    public record DocumentChange(String kbId, String docId, String fileName, Change change,
                                 AppCorpusDocument baseline, AppCorpusDocument candidate) { }
    /** 统计始终早于分页，任一侧不完整时不返回推断的差异。 */
    public record Comparison(CorpusSummary baseline, CorpusSummary candidate, boolean comparable, Long total,
                             Long added, Long removed, Long updated, int page, int pageSize, List<DocumentChange> items) { }
}
