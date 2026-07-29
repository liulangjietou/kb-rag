package io.kbrag.api.controller;

import io.kbrag.api.annotation.RequiresPermission;
import io.kbrag.api.dto.AppResponse;
import io.kbrag.api.dto.AppVersionConfigRequest;
import io.kbrag.api.dto.AppVersionResponse;
import io.kbrag.api.dto.ChatPreviewRequest;
import io.kbrag.api.dto.CreateAppRequest;
import io.kbrag.api.dto.KnowledgeChatResponse;
import io.kbrag.api.dto.UpdateAppRequest;
import io.kbrag.api.sse.SseChatStreamListener;
import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.constant.PermissionCodes;
import io.kbrag.domain.entity.App;
import io.kbrag.domain.entity.AppVersion;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Application management endpoints of the console, requirement section 4.7.
 *
 * <p>An application is not owned by a knowledge base - its configuration names the bases it retrieves
 * from - so authorisation here is the function level half only. The knowledge base level half is applied
 * where the configuration is used, when a preview or a released call actually retrieves.
 *
 * <p>The preview accepts {@code app:read} or {@code search:debug}: it is how a configuration is judged
 * before release, and the people who judge answers are not always the people who edit applications. Both
 * preview transports check the caller against the knowledge bases the configuration names before handing
 * the work over, because the streaming one leaves the request thread and takes the caller's identity with
 * it no further.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;
    private final AppVersionService appVersionService;
    private final KnowledgeApiService knowledgeApiService;

    /**
     * Creates an application.
     *
     * @param request name and description
     * @return created application
     */
    @PostMapping
    @RequiresPermission(PermissionCodes.APP_WRITE)
    public Result<AppResponse> create(@Valid @RequestBody CreateAppRequest request) {
        App app = appService.create(request.name(), request.description());
        return Result.success(AppResponse.from(app, null));
    }

    /**
     * Lists every application with its released version, newest first.
     *
     * @return applications
     */
    @GetMapping
    @RequiresPermission(PermissionCodes.APP_READ)
    public Result<List<AppResponse>> list() {
        List<AppResponse> items = appService.list().stream()
                .map(app -> AppResponse.from(app, appVersionService.currentReleased(app.getAppId())))
                .toList();
        return Result.success(items);
    }

    /**
     * Loads one application.
     *
     * @param appId application business id
     * @return application
     */
    @GetMapping("/{appId}")
    @RequiresPermission(PermissionCodes.APP_READ)
    public Result<AppResponse> detail(@PathVariable String appId) {
        App app = appService.require(appId);
        return Result.success(AppResponse.from(app, appVersionService.currentReleased(appId)));
    }

    /**
     * Renames an application or replaces its description.
     *
     * @param appId   application business id
     * @param request new name and description, a {@code null} keeps the stored value
     * @return updated application
     */
    @PutMapping("/{appId}")
    @RequiresPermission(PermissionCodes.APP_WRITE)
    public Result<AppResponse> update(@PathVariable String appId,
                                      @Valid @RequestBody UpdateAppRequest request) {
        App app = appService.update(appId, request.name(), request.description());
        return Result.success(AppResponse.from(app, appVersionService.currentReleased(appId)));
    }

    /**
     * Deletes an application and every one of its versions.
     *
     * @param appId application business id
     * @return empty success envelope
     */
    @DeleteMapping("/{appId}")
    @RequiresPermission(PermissionCodes.APP_WRITE)
    public Result<Void> delete(@PathVariable String appId) {
        appService.delete(appId);
        return Result.success(null);
    }

    /**
     * Creates a version from the submitted configuration; the console's configuration form.
     *
     * @param appId   application business id
     * @param request complete configuration, unset fields inherit the newest version's values
     * @return created draft version
     */
    @PostMapping("/{appId}/versions")
    @RequiresPermission(PermissionCodes.APP_WRITE)
    public Result<AppVersionResponse> createVersion(@PathVariable String appId,
                                                    @Valid @RequestBody AppVersionConfigRequest request) {
        appService.require(appId);
        AppVersion version = appVersionService.createDraft(appId, request.toSnapshot(),
                request.gateDatasetId(), request.changelog());
        return Result.success(AppVersionResponse.from(version));
    }

    /**
     * Lists the versions of an application, newest first.
     *
     * @param appId application business id
     * @return versions
     */
    @GetMapping("/{appId}/versions")
    @RequiresPermission(PermissionCodes.APP_READ)
    public Result<List<AppVersionResponse>> listVersions(@PathVariable String appId) {
        appService.require(appId);
        return Result.success(appVersionService.listByApp(appId).stream()
                .map(AppVersionResponse::from).toList());
    }

    /**
     * Chat preview of the console, reusing the open chat orchestration behind the console's own login.
     *
     * <p>One endpoint with two transports, exactly like the open chat endpoint: {@code stream} decides whether
     * the answer comes back as a body or as server sent events, and the event contract is identical, so the
     * console previews with the same code an external client would use.
     *
     * @param appId   application business id
     * @param request chat payload; {@code app_version_id} selects the version, absent takes the newest
     * @return generated answer with its references, or the event stream when {@code stream} is set
     */
    @PostMapping("/{appId}/chat-preview")
    @RequiresPermission({PermissionCodes.APP_READ, PermissionCodes.SEARCH_DEBUG})
    public ResponseEntity<Result<KnowledgeChatResponse>> chatPreview(@PathVariable String appId,
                                                                     @Valid @RequestBody ChatPreviewRequest request) {
        // The streaming variant lives on its own mapping selected by the accept header: Spring picks the
        // return value handler by the DECLARED type, so an SseEmitter smuggled through ResponseEntity<?>
        // is handed to the message converters and dies with HttpMessageNotWritableException - the previous
        // single-method shape never actually streamed. A body asking for a stream on the JSON mapping gets
        // an actionable rejection instead of a broken 500.
        if (request.isStream()) {
            throw BizException.invalidParam("stream=true requires the Accept: text/event-stream header");
        }
        knowledgeApiService.requirePreviewKbAccess(appId, request.getAppVersionId());
        return ResponseEntity.ok(Result.success(KnowledgeChatResponse.from(knowledgeApiService.preview(
                appId, request.getAppVersionId(), request.toCommand(), null))));
    }

    /**
     * Streaming console preview, selected when the client accepts {@code text/event-stream}.
     *
     * <p>The declared return type is the emitter itself - the only shape the async return value handler
     * recognises statically.
     *
     * @param appId   application business id
     * @param request chat payload
     * @return event stream of the generation
     */
    @PostMapping(value = "/{appId}/chat-preview", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission({PermissionCodes.APP_READ, PermissionCodes.SEARCH_DEBUG})
    public SseEmitter chatPreviewStream(@PathVariable String appId,
                                        @Valid @RequestBody ChatPreviewRequest request) {
        // Checked here and not inside the service: the generation runs on an executor that does not carry the
        // authenticated user across, so this is the last thread on which the caller's scope is still known.
        knowledgeApiService.requirePreviewKbAccess(appId, request.getAppVersionId());
        SseChatStreamListener listener = new SseChatStreamListener();
        knowledgeApiService.previewStreamAsync(appId, request.getAppVersionId(), request.toCommand(), listener);
        return listener.emitter();
    }
}
