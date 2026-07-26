package io.kbrag.api.controller;

import io.kbrag.api.dto.AppVersionResponse;
import io.kbrag.api.dto.GateDatasetRequest;
import io.kbrag.api.filter.AuthInterceptor;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.appcenter.ReleaseGateService;
import io.kbrag.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application version state machine endpoints of the console, requirement section 4.7.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/app-versions")
@RequiredArgsConstructor
public class AppVersionController {

    private final AppVersionService appVersionService;
    private final ReleaseGateService releaseGateService;

    /**
     * Loads one version with its gate outcome.
     *
     * @param appVersionId version business id
     * @return version
     */
    @GetMapping("/{appVersionId}")
    public Result<AppVersionResponse> detail(@PathVariable String appVersionId) {
        return Result.success(AppVersionResponse.from(appVersionService.require(appVersionId)));
    }

    /**
     * Binds or clears the baseline evaluation data set of the release gate.
     *
     * @param appVersionId version business id
     * @param request      data set to bind, blank clears the binding
     * @return updated version
     */
    @PutMapping("/{appVersionId}/gate-dataset")
    public Result<AppVersionResponse> gateDataset(@PathVariable String appVersionId,
                                                 @Valid @RequestBody GateDatasetRequest request) {
        return Result.success(AppVersionResponse.from(
                appVersionService.setGateDataset(appVersionId, request.datasetId())));
    }

    /**
     * Moves a draft to the test version state, freezing its configuration snapshot.
     *
     * @param appVersionId version business id
     * @return updated version
     */
    @PostMapping("/{appVersionId}/submit-test")
    public Result<AppVersionResponse> submitTest(@PathVariable String appVersionId) {
        return Result.success(AppVersionResponse.from(appVersionService.submitTest(appVersionId)));
    }

    /**
     * Releases a version, running the gate first when one applies.
     *
     * <p>The response is the version as it stands when the call returns, which for a gated release is
     * {@code GATING}: the dual run proceeds asynchronously and the console polls the detail endpoint. A verdict
     * that is not a pass leaves the version in its gate outcome state until an operator calls again with
     * {@code force=true}, which is recorded on the row together with who did it.
     *
     * @param appVersionId version business id
     * @param force        {@code true} releases despite a non passing verdict
     * @param request      current request, source of the authenticated operator name
     * @return version state after this call
     */
    @PostMapping("/{appVersionId}/release")
    public Result<AppVersionResponse> release(@PathVariable String appVersionId,
                                             @RequestParam(defaultValue = "false") boolean force,
                                             HttpServletRequest request) {
        String operator = (String) request.getAttribute(AuthInterceptor.ATTR_USERNAME);
        return Result.success(AppVersionResponse.from(
                releaseGateService.release(appVersionId, force, operator)));
    }

    /**
     * Rolls back to a retired version, making it the released one again.
     *
     * @param appVersionId target version business id
     * @return restored version
     */
    @PostMapping("/{appVersionId}/rollback")
    public Result<AppVersionResponse> rollback(@PathVariable String appVersionId) {
        return Result.success(AppVersionResponse.from(appVersionService.rollback(appVersionId)));
    }
}
