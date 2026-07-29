package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AlertConfigResponse;
import io.kbrag.api.dto.DemoStatusResponse;
import io.kbrag.api.dto.ModelStatusResponse;
import io.kbrag.api.dto.UpdateAlertConfigRequest;
import io.kbrag.app.alert.AlertConfigService;
import io.kbrag.app.alert.AlertService;
import io.kbrag.app.system.DemoImportService;
import io.kbrag.app.system.ModelStatusService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.model.AlertConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * System information, alert settings and demo data endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private static final String FIELD_KB_ID = "kb_id";
    private static final String TEST_MESSAGE =
            "test alert from the knowledge base console, the webhook is wired correctly";

    private final ModelStatusService modelStatusService;
    private final AlertConfigService alertConfigService;
    private final AlertService alertService;
    private final DemoImportService demoImportService;

    /**
     * Reports which model capabilities are configured and which engine backs the vector route.
     *
     * @return model status
     */
    @GetMapping("/model-status")
    // Readable by anyone who can open a knowledge base too: the retrieval screens explain a missing
    // capability with this answer, and an operator without system rights would otherwise see a blank reason.
    @RequiresPermission({PermissionCodes.SYSTEM_CONFIG, PermissionCodes.KB_READ})
    public Result<ModelStatusResponse> modelStatus() {
        return Result.success(ModelStatusResponse.from(modelStatusService.current()));
    }

    /**
     * Reads the alert settings.
     *
     * @return current settings
     */
    @GetMapping("/alert-config")
    @RequiresPermission(PermissionCodes.SYSTEM_CONFIG)
    public Result<AlertConfigResponse> alertConfig() {
        return Result.success(AlertConfigResponse.from(alertConfigService.current()));
    }

    /**
     * Replaces the alert settings.
     *
     * @param request new settings
     * @return stored settings
     */
    @PutMapping("/alert-config")
    @RequiresPermission(PermissionCodes.SYSTEM_CONFIG)
    public Result<AlertConfigResponse> updateAlertConfig(
            @Valid @RequestBody UpdateAlertConfigRequest request) {
        AlertConfig updated = alertConfigService.update(
                request.toAlertConfig(alertConfigService.current()));
        return Result.success(AlertConfigResponse.from(updated));
    }

    /**
     * Sends a test message to the configured webhook.
     *
     * <p>The silence window is deliberately bypassed: the operator clicked the button and expects a message,
     * and suppressing it would look like a broken configuration.
     *
     * <p>A delivery failure is reported rather than swallowed. An automatic alert degrades to an error log
     * because the caller is an indexing thread that must not fail over a notification, but here the caller is
     * a human asking whether the wiring works, and answering "success" over a refused connection would be a
     * wrong answer to that question.
     *
     * @return empty success envelope
     */
    @PostMapping("/alert-config/test")
    @RequiresPermission(PermissionCodes.SYSTEM_CONFIG)
    public Result<Void> testAlert() {
        if (!alertService.sendTest(TEST_MESSAGE)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "告警 webhook 发送失败，请检查 URL 是否可达");
        }
        return Result.success(null);
    }

    /**
     * Imports the bundled demo document set, or returns the knowledge base a previous run created.
     *
     * @return knowledge base business id
     */
    @PostMapping("/demo/import")
    @RequiresPermission(PermissionCodes.SYSTEM_CONFIG)
    public Result<Map<String, String>> importDemo() {
        return Result.success(Map.of(FIELD_KB_ID, demoImportService.importDemo()));
    }

    /**
     * Reports whether the demo can be imported and whether it already was.
     *
     * @return demo state
     */
    @GetMapping("/demo/status")
    @RequiresPermission({PermissionCodes.SYSTEM_CONFIG, PermissionCodes.KB_READ})
    public Result<DemoStatusResponse> demoStatus() {
        return Result.success(DemoStatusResponse.from(demoImportService.status()));
    }
}
