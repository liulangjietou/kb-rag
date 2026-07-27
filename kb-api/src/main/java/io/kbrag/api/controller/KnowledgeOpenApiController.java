package io.kbrag.api.controller;

import io.kbrag.api.dto.KnowledgeCallRequest;
import io.kbrag.api.dto.KnowledgeChatResponse;
import io.kbrag.api.dto.KnowledgeSearchResponse;
import io.kbrag.api.filter.ApiKeyAuthFilter;
import io.kbrag.api.sse.SseChatStreamListener;
import io.kbrag.app.openapi.ApiKeyPrincipal;
import io.kbrag.app.openapi.KnowledgeApiService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.api.Result;
import io.kbrag.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open API, requirement section 4.8.
 *
 * <p>Authentication, the key's quota and its authorisation scope are handled before and around this class - the
 * filter for the credential and the rate limit, the application service for the scope - so the controller only
 * validates payload shape and picks the transport (a body or a stream).
 *
 * <p>{@code /chat} returns either a body or a stream from the same mapping, decided by the {@code stream} flag,
 * because the requirement defines one endpoint with two transports rather than two endpoints.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeOpenApiController {

    private static final String EVENT_STREAM_CONTENT_TYPE = "text/event-stream";

    private final KnowledgeApiService knowledgeApiService;

    /**
     * Retrieval only call.
     *
     * @param request  search payload
     * @param httpRequest current request, source of the authenticated caller
     * @return nodes, degradation markers and the version that served the call
     */
    @PostMapping("/search")
    public Result<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeCallRequest request,
                                                 HttpServletRequest httpRequest) {
        requireAppId(request);
        if (request.isStream()) {
            throw BizException.invalidParam("search 接口不支持 stream，流式响应仅 chat 接口提供");
        }
        return Result.success(KnowledgeSearchResponse.from(
                knowledgeApiService.search(principalOf(httpRequest), request.toCommand())));
    }

    /**
     * Retrieval augmented generation call, body or stream.
     *
     * @param request     chat payload
     * @param httpRequest current request, source of the authenticated caller
     * @return response body, or the server sent event stream when {@code stream} is set
     */
    @PostMapping("/chat")
    public ResponseEntity<Result<KnowledgeChatResponse>> chat(@Valid @RequestBody KnowledgeCallRequest request,
                                                              HttpServletRequest httpRequest) {
        requireAppId(request);
        ApiKeyPrincipal principal = principalOf(httpRequest);
        // Streaming has its own mapping picked by the accept header; see chatStream. Spring resolves the
        // return value handler from the DECLARED type, so an SseEmitter behind ResponseEntity<?> was fed
        // to the message converters and failed with HttpMessageNotWritableException - the earlier
        // single-method contract of "stream despite a JSON accept header" never actually worked.
        if (request.isStream()) {
            throw BizException.invalidParam("stream=true requires the Accept: text/event-stream header");
        }
        return ResponseEntity.ok(Result.success(KnowledgeChatResponse.from(
                knowledgeApiService.chat(principal, request.toCommand()))));
    }

    /**
     * Streaming retrieval augmented generation, selected when the client accepts {@code text/event-stream}.
     *
     * @param request     chat payload
     * @param httpRequest current request, source of the authenticated caller
     * @return event stream of the generation
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody KnowledgeCallRequest request,
                                 HttpServletRequest httpRequest) {
        requireAppId(request);
        ApiKeyPrincipal principal = principalOf(httpRequest);
        SseChatStreamListener listener = new SseChatStreamListener();
        knowledgeApiService.chatStreamAsync(principal, request.toCommand(), listener);
        return listener.emitter();
    }

    /**
     * Reads the caller the filter authenticated.
     *
     * <p>Absent means the filter did not run, which can only happen if this controller is ever mapped outside
     * the open API prefix; failing loudly is the only safe answer to that, since continuing would serve an
     * unauthenticated call.
     *
     * @param request current request
     * @return authenticated caller
     */
    private ApiKeyPrincipal principalOf(HttpServletRequest request) {
        Object principal = request.getAttribute(ApiKeyAuthFilter.ATTR_PRINCIPAL);
        if (!(principal instanceof ApiKeyPrincipal resolved)) {
            log.error("open api reached without an authenticated key, errorCode={}, uri={}",
                    ErrorCode.INVALID_API_KEY, request.getRequestURI());
            throw new BizException(ErrorCode.INVALID_API_KEY, "API Key 鉴权未通过");
        }
        return resolved;
    }

    private void requireAppId(KnowledgeCallRequest request) {
        if (request.getAppId() == null || request.getAppId().isBlank()) {
            throw BizException.invalidParam("app_id must not be blank");
        }
    }
}
