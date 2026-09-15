package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.KnowledgeTodoResponse;
import io.kbrag.app.workspace.KnowledgeTodoService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理工作台待办摘要，员工工作台保持独立权限与数据边界。 */
@RestController
@RequestMapping("/api/v1/me/knowledge-todos")
@RequiresPermission(PermissionCodes.KB_READ)
@RequiredArgsConstructor
public class KnowledgeTodoController {
    private final KnowledgeTodoService todos;

    /** 撤权后不得从浏览器或代理缓存恢复旧摘要。 */
    @GetMapping
    public ResponseEntity<Result<List<KnowledgeTodoResponse>>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(todos.list().stream().map(KnowledgeTodoResponse::from).toList()));
    }
}
