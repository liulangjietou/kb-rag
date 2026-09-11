package io.kbrag.domain.model;

import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;

/** 接受问题时固定的执行配置，属于内部数据，不能作为 HTTP 响应返回。 */
public record EmployeeRunTarget(String appId, String appVersionId, String appVersion,
                                String config, String indexSnapshots, String visibleVersionIds,
                                boolean snapshotBound) {

    /** 捕获正式版本，后续发布或快照清理不能改变已接受运行的读取语义。 */
    public static EmployeeRunTarget capture(AppVersion version) {
        if (version.getStatus() != AppVersionStatus.RELEASED) {
            throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED, "application version not published");
        }
        return new EmployeeRunTarget(version.getAppId(), version.getAppVersionId(), version.getVersion(),
                version.getConfig(), version.getIndexSnapshots(), version.getVisibleVersionIds(),
                version.hasIndexSnapshot());
    }
}
