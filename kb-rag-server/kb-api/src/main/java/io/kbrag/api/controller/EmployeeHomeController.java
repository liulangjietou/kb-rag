package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.EmployeeHomeResponse;
import io.kbrag.app.workspace.EmployeeHomeService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 普通员工首页，管理应用权限不替代应用使用权限。 */
@RestController
@RequiredArgsConstructor
public class EmployeeHomeController {
    private final EmployeeHomeService home;

    /** 当前授权范围下的可用应用及最近五个私有会话；HTTP 缓存不可保留旧权限视图。 */
    @GetMapping("/api/v1/workspace/overview")
    @RequiresPermission(PermissionCodes.APP_USE)
    public ResponseEntity<Result<EmployeeHomeResponse>> overview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(EmployeeHomeResponse.from(home.overview())));
    }
}
