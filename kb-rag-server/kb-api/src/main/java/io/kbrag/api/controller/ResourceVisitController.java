package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.RecordResourceVisitRequest;
import io.kbrag.api.dto.ResourceVisitResponse;
import io.kbrag.app.workspace.ResourceVisitService;
import io.kbrag.common.api.Result;
import io.kbrag.domain.constant.PermissionCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端的个人访问记录；员工私有会话继续使用工作台接口。 */
@RestController
@RequestMapping("/api/v1/me/resource-visits")
@RequiresPermission({PermissionCodes.KB_READ, PermissionCodes.APP_READ})
@RequiredArgsConstructor
public class ResourceVisitController {
    private final ResourceVisitService visits;

    /** 每次读取重新校验资源可见性，禁止缓存保留旧权限视图。 */
    @GetMapping
    public ResponseEntity<Result<List<ResourceVisitResponse>>> recent() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(visits.recent().stream().map(ResourceVisitResponse::from).toList()));
    }

    /** 记录已打开的资源，具体种类所需权限仍在服务入口校验。 */
    @PostMapping
    public ResponseEntity<Result<Void>> remember(@Valid @RequestBody RecordResourceVisitRequest request) {
        visits.remember(request.kind(), request.resourceId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(null));
    }

    /** 用户主动清空本人历史，既不清空其他用户也不删除知识资源。 */
    @DeleteMapping
    public ResponseEntity<Result<Void>> clear() {
        visits.clear();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(null));
    }
}
