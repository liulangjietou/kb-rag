package io.kbrag.api.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.app.workspace.EmployeeAppCatalogService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 普通员工的正式应用入口，与编辑和调试目录分别授权。 */
@RestController
@RequestMapping("/api/v1/workspace/apps")
@RequiredArgsConstructor
@RequiresPermission(PermissionCodes.APP_USE)
public class EmployeeAppCatalogController {
    private final EmployeeAppCatalogService catalog;

    /** 仅返回当前有权使用的应用及正式版本标识，不返回模型、提示词或检索配置。 */
    @GetMapping
    public Result<List<ApplicationResponse>> list() {
        return Result.success(catalog.list().stream().map(item -> new ApplicationResponse(
                item.app().getAppId(), item.app().getName(), item.app().getDescription(),
                item.version().getAppVersionId(), item.version().getVersion())).toList());
    }

    /** 员工入口的最小展示字段。 */
    public record ApplicationResponse(@JsonProperty("app_id") String appId, String name, String description,
                                      @JsonProperty("released_version_id") String releasedVersionId,
                                      @JsonProperty("released_version") String releasedVersion) {
    }
}
