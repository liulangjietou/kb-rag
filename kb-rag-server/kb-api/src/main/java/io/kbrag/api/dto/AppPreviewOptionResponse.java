package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.appcenter.AppPreviewCatalogService.PreviewApplication;

import java.util.List;

/** 可调试应用摘要，不包含配置、提示词或发布门禁详情。 */
public record AppPreviewOptionResponse(
        @JsonProperty("app_id") String appId,
        String name,
        List<VersionOption> versions) {

    /** 将已授权目录项映射为应用和版本选择器使用的摘要。 */
    public static AppPreviewOptionResponse from(PreviewApplication item) {
        return new AppPreviewOptionResponse(item.app().getAppId(), item.app().getName(),
                item.versions().stream().map(version -> new VersionOption(
                        version.getAppVersionId(), version.getVersion(), version.getStatus().name())).toList());
    }

    /** 版本显示标签和实际调用 ID 分开，预览始终使用当前活动语料。 */
    public record VersionOption(
            @JsonProperty("app_version_id") String appVersionId,
            String version,
            String status) {
    }
}
