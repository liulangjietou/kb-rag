package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AppPreviewOptionResponse;
import io.kbrag.app.appcenter.AppPreviewCatalogService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 问答调试专用目录，与应用管理接口保持独立的最小权限入口。 */
@RestController
@RequestMapping("/api/v1/app-previews")
@RequiredArgsConstructor
public class AppPreviewCatalogController {

    private final AppPreviewCatalogService service;

    /** 返回登录用户有权调试的应用与版本摘要。 */
    @GetMapping
    @RequiresPermission({PermissionCodes.APP_READ, PermissionCodes.SEARCH_DEBUG})
    public Result<List<AppPreviewOptionResponse>> list() {
        return Result.success(service.list().stream().map(AppPreviewOptionResponse::from).toList());
    }
}
