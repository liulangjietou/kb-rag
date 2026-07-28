package io.kbrag.app.openapi;

import io.kbrag.app.appcenter.AppService;
import io.kbrag.app.appcenter.AppVersionService;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.insight.SearchInsightService;
import io.kbrag.app.metrics.KbMetrics;
import io.kbrag.app.retrieval.ImageQueryService;
import io.kbrag.app.retrieval.RetrievalCommand;
import io.kbrag.app.retrieval.RetrievalIndexOverride;
import io.kbrag.app.retrieval.RetrievalNodeView;
import io.kbrag.app.retrieval.RetrievalService;
import io.kbrag.app.retrieval.SearchOutcome;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.context.RequestIdHolder;
import io.kbrag.common.exception.BizException;
import io.kbrag.domain.entity.AppVersion;
import io.kbrag.domain.enums.AppVersionStatus;
import io.kbrag.domain.enums.InsightSource;
import io.kbrag.domain.enums.TargetStage;
import io.kbrag.domain.model.AppConfigSnapshot;
import io.kbrag.domain.model.AppIndexSnapshot;
import io.kbrag.domain.model.AppRoutingConfig;
import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.KbRef;
import io.kbrag.domain.model.KbRetrievalConfig;
import io.kbrag.domain.port.ChatProvider;
import io.kbrag.domain.port.ChatProviderFactory;
import io.kbrag.domain.service.ChatPromptAssembler;
import io.kbrag.domain.service.ContentBudgetTrimmer;
import io.kbrag.domain.service.RequestOverridePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The open API's search and chat orchestration, requirement section 4.8.
 *
 * <p><b>The version snapshot decides, the request may only narrow.</b> Every retrieval parameter comes from
 * the frozen configuration of the application version that serves the call; the four white listed overrides
 * shape the response and nothing else. That is what makes a release gate verdict true for the traffic actually
 * served, and it is why an attempt to override anything else is rejected instead of ignored.
 *
 * <p><b>The retrieval pipeline is reused, not re-implemented.</b> Both endpoints go through
 * {@link RetrievalService}, so the open API cannot drift away from the console's debug page, and both report
 * the same {@code RetrievalNode} schema - the search's {@code nodes} and the chat's {@code references} are
 * literally the same objects.
 *
 * <p><b>Every call is audited, including the rejected ones.</b> The audit row is written after completion and
 * off the request thread; a scope violation produces a row carrying its error code, because an unauthorised
 * call is exactly what an audit trail exists to show.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeApiService {

    private final AppService appService;
    private final AppVersionService appVersionService;
    private final RetrievalService retrievalService;
    private final ChatProviderFactory chatProviderFactory;
    private final ChatPromptAssembler chatPromptAssembler;
    private final ContentBudgetTrimmer contentBudgetTrimmer;
    private final RequestOverridePolicy requestOverridePolicy;
    private final ApiAuditService apiAuditService;
    private final SearchInsightService searchInsightService;
    private final ImageQueryService imageQueryService;
    private final KbMetrics kbMetrics;

    /**
     * Runs one open search call.
     *
     * @param principal authenticated caller
     * @param command   call parameters
     * @return nodes, degradation markers and the version that served the call
     */
    public KnowledgeCallResult search(ApiKeyPrincipal principal, KnowledgeCallCommand command) {
        long startedAt = System.currentTimeMillis();
        EnrichedCall call = EnrichedCall.of(command);
        try {
            ResolvedTarget target = resolve(principal, command);
            call = enrich(command);
            KnowledgeCallResult result = retrieve(target, call);
            insight(call, result, startedAt);
            audit(principal, call.command(), target, result, startedAt, ApiAuditService.ENDPOINT_SEARCH);
            return result;
        } catch (BizException e) {
            auditRejection(principal, call.command(), startedAt, e, ApiAuditService.ENDPOINT_SEARCH);
            throw e;
        }
    }

    /**
     * Runs one open chat call without streaming.
     *
     * @param principal authenticated caller
     * @param command   call parameters
     * @return answer, references, degradation markers and the version that served the call
     */
    public KnowledgeCallResult chat(ApiKeyPrincipal principal, KnowledgeCallCommand command) {
        long startedAt = System.currentTimeMillis();
        EnrichedCall call = EnrichedCall.of(command);
        try {
            ResolvedTarget target = resolve(principal, command);
            call = enrich(command);
            KnowledgeCallResult retrieved = retrieve(target, call);
            insight(call, retrieved, startedAt);
            String answer = generate(target, call.command(), retrieved.getNodes());
            KnowledgeCallResult result = withAnswer(retrieved, answer);
            audit(principal, call.command(), target, result, startedAt, ApiAuditService.ENDPOINT_CHAT);
            return result;
        } catch (BizException e) {
            auditRejection(principal, call.command(), startedAt, e, ApiAuditService.ENDPOINT_CHAT);
            throw e;
        }
    }

    /**
     * Runs one open chat call as a stream.
     *
     * <p>Event order is fixed: the generated pieces, then the references, then exactly one terminal event. A
     * failure after the first delta still terminates with {@code error} rather than {@code done}, so a client
     * never treats a truncated answer as a complete one.
     *
     * @param principal authenticated caller
     * @param command   call parameters
     * @param listener  receiver of the stream
     */
    public void chatStream(ApiKeyPrincipal principal, KnowledgeCallCommand command,
                           ChatStreamListener listener) {
        long startedAt = System.currentTimeMillis();
        EnrichedCall call = EnrichedCall.of(command);
        try {
            ResolvedTarget target = resolve(principal, command);
            call = enrich(command);
            KnowledgeCallResult retrieved = retrieve(target, call);
            insight(call, retrieved, startedAt);
            StringBuilder answer = new StringBuilder();
            streamGenerate(target, call.command(), retrieved.getNodes(), delta -> {
                answer.append(delta);
                listener.onDelta(delta);
            });
            listener.onReferences(retrieved.getNodes());
            listener.onDone(RequestIdHolder.get(), retrieved.getDegraded(), retrieved.routedKbIds());
            audit(principal, call.command(), target, retrieved, startedAt, ApiAuditService.ENDPOINT_CHAT);
        } catch (BizException e) {
            auditRejection(principal, call.command(), startedAt, e, ApiAuditService.ENDPOINT_CHAT);
            listener.onError(e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("open chat stream failed, errorCode={}, appId={}",
                    ErrorCode.INTERNAL_ERROR, command.getAppId(), e);
            auditRejection(principal, call.command(), startedAt,
                    new BizException(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage()),
                    ApiAuditService.ENDPOINT_CHAT);
            listener.onError(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        }
    }

    /**
     * Runs a streamed chat call off the request thread.
     *
     * <p>Needed because the transport hands back a stream handle and only then starts writing to it: running the
     * generation on the request thread would collect every event first and deliver them together, which is a
     * stream in shape only.
     *
     * @param principal authenticated caller
     * @param command   call parameters
     * @param listener  receiver of the stream
     */
    @Async(AsyncConfig.CHAT_STREAM_EXECUTOR)
    public void chatStreamAsync(ApiKeyPrincipal principal, KnowledgeCallCommand command,
                                ChatStreamListener listener) {
        chatStream(principal, command, listener);
    }

    /**
     * Runs a streamed console preview off the request thread.
     *
     * @param appId        application business id
     * @param appVersionId version to preview, {@code null} takes the newest one
     * @param command      call parameters
     * @param listener     receiver of the stream
     */
    @Async(AsyncConfig.CHAT_STREAM_EXECUTOR)
    public void previewStreamAsync(String appId, String appVersionId, KnowledgeCallCommand command,
                                   ChatStreamListener listener) {
        try {
            preview(appId, appVersionId, command, listener);
        } catch (BizException e) {
            listener.onError(e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("chat preview stream failed, errorCode={}, appId={}",
                    ErrorCode.INTERNAL_ERROR, appId, e);
            listener.onError(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        }
    }

    /**
     * Console side chat preview, requirement section 7 "question answering debug page".
     *
     * <p>Reuses the exact orchestration of the open endpoint - resolution of the snapshot, prompt assembly,
     * generation - so what an operator previews is what an external agent receives. It differs in two ways
     * only, both of them deliberate: it is reached through the console's own authentication instead of an API
     * key, and it may target a version that is not released yet, since previewing a draft is the point.
     *
     * @param appId        application business id
     * @param appVersionId version to preview, {@code null} takes the newest version of the application
     * @param command      call parameters, {@code appId} and {@code appVersion} ignored
     * @param listener     stream receiver, {@code null} for a non streamed preview
     * @return result; {@code answer} is {@code null} when a listener consumed the stream instead
     */
    public KnowledgeCallResult preview(String appId, String appVersionId, KnowledgeCallCommand command,
                                       ChatStreamListener listener) {
        appService.require(appId);
        AppVersion version = appVersionId == null || appVersionId.isBlank()
                ? appVersionService.requireNewest(appId)
                : appVersionService.require(appVersionId);
        // Deliberately not snapshot bound even when the previewed version is the released one: a preview exists
        // to try a configuration against the corpus as it is now, and it is also how an operator proves a
        // snapshot isolates a release - the same query returns the new content here and does not there
        // (requirement section 4.4 "the console debug page takes the current active versions").
        ResolvedTarget target = new ResolvedTarget(version, appVersionService.parseConfig(version),
                TargetStage.of(version.getStatus()), false);
        requestOverridePolicy.validate(forbiddenKeysOf(command));
        EnrichedCall call = enrich(command);
        KnowledgeCallResult retrieved = retrieve(target, call);
        if (listener == null) {
            return withAnswer(retrieved, generate(target, call.command(), retrieved.getNodes()));
        }
        streamGenerate(target, call.command(), retrieved.getNodes(), listener::onDelta);
        listener.onReferences(retrieved.getNodes());
        listener.onDone(RequestIdHolder.get(), retrieved.getDegraded(), retrieved.routedKbIds());
        return retrieved;
    }

    /**
     * Resolves the application, the authorisation and the serving version of one call.
     *
     * <p>The scope check runs before the application lookup: answering "this application does not exist" to a
     * caller that is not authorised for it would confirm or deny the existence of applications outside its
     * scope.
     *
     * @param principal authenticated caller
     * @param command   call parameters
     * @return resolved target
     */
    private ResolvedTarget resolve(ApiKeyPrincipal principal, KnowledgeCallCommand command) {
        principal.requireAccessTo(command.getAppId());
        requestOverridePolicy.validate(forbiddenKeysOf(command));
        appService.require(command.getAppId());
        AppVersion version = appVersionService.resolveForCall(command.getAppId(), command.getAppVersion());
        return new ResolvedTarget(version, appVersionService.parseConfig(version),
                TargetStage.of(version.getStatus()), snapshotBound(version));
    }

    /**
     * Decides whether a call is served out of the version's frozen index snapshot, requirement section 4.7.
     *
     * <p>Two conditions, both necessary. Only a <b>released</b> version serves a snapshot: a beta call against a
     * test version is a gradual rollout against the current corpus, and a test version has no snapshot anyway
     * since snapshots are created by the release. And only a version that actually <b>froze</b> one: a version
     * released before this milestone carries nothing, which is a historical data shape rather than a fault, so
     * it falls through to the live alias without a degradation marker.
     *
     * @param version version serving the call
     * @return {@code true} when the frozen snapshot is what this call must read
     */
    private boolean snapshotBound(AppVersion version) {
        return version.getStatus() == AppVersionStatus.RELEASED && version.hasIndexSnapshot();
    }

    /**
     * Folds the attached images into the question before anything else reads it, requirement section 4.8.
     *
     * <p>Runs after the authorisation check and before the retrieval, which is what makes the image text
     * reach the rewrite stage, the recall routes, the generation prompt and the audit digest as one single
     * question. Every consumer downstream therefore sees the same string and none of them has to know that
     * an image was involved.
     *
     * @param command call parameters as the caller expressed them
     * @return the same call with an enriched query, plus whatever the image stage degraded
     */
    private EnrichedCall enrich(KnowledgeCallCommand command) {
        ImageQueryService.ImageQueryOutcome outcome =
                imageQueryService.enrich(command.getQuery(), command.getImages());
        return new EnrichedCall(command.toBuilder().query(outcome.query()).build(), outcome.degraded());
    }

    /**
     * Runs the retrieval stage of one call against the frozen configuration.
     *
     * @param target resolved application version
     * @param call   call parameters with the image text already folded into the query
     * @return result without an answer
     */
    private KnowledgeCallResult retrieve(ResolvedTarget target, EnrichedCall call) {
        KnowledgeCallCommand command = call.command();
        AppConfigSnapshot snapshot = target.snapshot();
        List<KbRef> kbRefs = snapshot.getKbRefs();
        if (CollectionUtils.isEmpty(kbRefs)) {
            throw new BizException(ErrorCode.VERSION_NOT_PUBLISHED,
                    "应用版本未配置知识库，无法提供检索服务");
        }
        SearchOutcome outcome = retrievalService.search(kbRefs, toRetrievalCommand(snapshot, command, target));
        List<RetrievalNodeView> nodes = trim(outcome.getNodes(), command.getMaxContentLength());
        return KnowledgeCallResult.builder()
                .nodes(nodes)
                .degraded(mergeDegraded(call.imageDegraded(), outcome.getDegraded()))
                .applied(outcome.getApplied())
                .appVersionId(target.version().getAppVersionId())
                .appVersion(target.version().getVersion())
                .targetStage(target.stage())
                .build();
    }

    /**
     * Joins the markers of the image stage with the ones the pipeline produced.
     *
     * <p>The image stage runs outside {@code RetrievalService}, so its marker cannot travel in the search
     * outcome; joining here keeps the caller with one flat {@code degraded} array whatever produced it.
     *
     * @param imageDegraded markers of the image stage
     * @param pipeline      markers of the retrieval pipeline
     * @return degradation markers of the whole call, without duplicates
     */
    private List<String> mergeDegraded(List<String> imageDegraded, List<String> pipeline) {
        if (CollectionUtils.isEmpty(imageDegraded)) {
            return pipeline;
        }
        Set<String> merged = new LinkedHashSet<>(imageDegraded);
        if (CollectionUtils.isNotEmpty(pipeline)) {
            merged.addAll(pipeline);
        }
        return List.copyOf(merged);
    }

    /**
     * Maps the frozen snapshot plus the white listed overrides onto the retrieval command.
     *
     * <p>Note what is <em>not</em> read from the request: {@code recall_top_k}, the fusion strategy and its
     * parameters, the rerank and rewrite switches. They come from the snapshot alone, which is the whole
     * substance of the configuration freeze.
     *
     * @param snapshot frozen configuration
     * @param command  call parameters
     * @param target   resolved application version, read for the index snapshot binding
     * @return retrieval command
     */
    private RetrievalCommand toRetrievalCommand(AppConfigSnapshot snapshot, KnowledgeCallCommand command,
                                                ResolvedTarget target) {
        KbRetrievalConfig retrieval = snapshot.retrievalOrDefaults();
        AppRoutingConfig routing = snapshot.routingOrDefaults();
        return RetrievalCommand.builder()
                .indexOverride(target.snapshotBound() ? indexOverridesOf(target.version()) : null)
                .visibleVersionIdsOverride(target.snapshotBound()
                        ? target.version().visibleVersionIdMap() : null)
                .routingEnabled(routing.isEnabled())
                .routingPrompt(routing.getPrompt())
                .query(command.getQuery())
                .messages(command.getMessages() == null ? List.of() : command.getMessages())
                .recallTopK(retrieval.getRecallTopK())
                .topN(command.getTopN() != null ? command.getTopN() : retrieval.getTopN())
                .scoreThreshold(command.getScoreThreshold() != null
                        ? command.getScoreThreshold() : retrieval.getScoreThreshold())
                .fusionMode(retrieval.getFusionMode())
                .wVec(retrieval.getWVec())
                .rrfK(retrieval.getRrfK())
                .rerankEnabled(retrieval.getRerankEnabled())
                .rewriteEnabled(retrieval.getRewriteEnabled())
                .metadataFilter(command.getMetadataFilter())
                .build();
    }

    /**
     * Groups the frozen snapshots of a version into the per knowledge base index override.
     *
     * @param version released version carrying a snapshot
     * @return override per knowledge base id, bases without a usable snapshot left out
     */
    private Map<String, RetrievalIndexOverride> indexOverridesOf(AppVersion version) {
        Map<String, List<AppIndexSnapshot>> byKb = new LinkedHashMap<>();
        for (AppIndexSnapshot snapshot : version.indexSnapshotList()) {
            byKb.computeIfAbsent(snapshot.kbId(), key -> new ArrayList<>()).add(snapshot);
        }
        Map<String, RetrievalIndexOverride> overrides = new LinkedHashMap<>(byKb.size());
        byKb.forEach((kbId, snapshots) -> {
            RetrievalIndexOverride override = RetrievalIndexOverride.of(snapshots);
            if (override != null) {
                overrides.put(kbId, override);
            }
        });
        return overrides;
    }

    /**
     * Applies the content budget to a ranked node list.
     *
     * @param nodes            ranked nodes
     * @param maxContentLength budget in characters, {@code null} disables trimming
     * @return the kept prefix of the list
     */
    private List<RetrievalNodeView> trim(List<RetrievalNodeView> nodes, Integer maxContentLength) {
        if (CollectionUtils.isEmpty(nodes) || maxContentLength == null) {
            return nodes;
        }
        List<Integer> lengths = new ArrayList<>(nodes.size());
        for (RetrievalNodeView node : nodes) {
            lengths.add(node.getContent() == null ? 0 : node.getContent().length());
        }
        int keep = contentBudgetTrimmer.keepCount(lengths, maxContentLength);
        if (keep >= nodes.size()) {
            return nodes;
        }
        log.info("open api response trimmed to the content budget, budget={}, nodes={}, kept={}",
                maxContentLength, nodes.size(), keep);
        return nodes.subList(0, keep);
    }

    /**
     * Generates one answer.
     *
     * @param target resolved application version
     * @param command call parameters
     * @param nodes  retrieved material
     * @return generated answer
     */
    private String generate(ResolvedTarget target, KnowledgeCallCommand command,
                            List<RetrievalNodeView> nodes) {
        ChatProvider provider = requireChatProvider(target);
        return provider.complete(chatPromptAssembler.systemPrompt(target.snapshot().promptOrDefaults()),
                promptMessages(command, nodes));
    }

    /**
     * Generates one answer as a stream.
     *
     * @param target   resolved application version
     * @param command  call parameters
     * @param nodes    retrieved material
     * @param onDelta  receiver of the generated pieces
     */
    private void streamGenerate(ResolvedTarget target, KnowledgeCallCommand command,
                                List<RetrievalNodeView> nodes, java.util.function.Consumer<String> onDelta) {
        ChatProvider provider = requireChatProvider(target);
        provider.stream(chatPromptAssembler.systemPrompt(target.snapshot().promptOrDefaults()),
                promptMessages(command, nodes), onDelta);
    }

    /**
     * Resolves the generation model of a version, or explains why generation is unavailable.
     *
     * <p>The zero key deployment is a supported state for retrieval and an unsupported one for generation, so
     * the failure is explicit and classified rather than an empty answer: an agent has to be able to tell "no
     * model configured" from "the model had nothing to say".
     *
     * @param target resolved application version
     * @return configured chat provider
     */
    private ChatProvider requireChatProvider(ResolvedTarget target) {
        ChatProvider provider = chatProviderFactory.forModel(target.snapshot().getChatModel());
        if (!provider.isConfigured()) {
            throw new BizException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "问答生成不可用：当前部署未配置对话模型（零 Key 模式），"
                            + "请在系统设置中配置对话模型 Provider 后重试；检索接口不受影响");
        }
        return provider;
    }

    /**
     * Builds the message list of a generation call: the caller's history plus the assembled question.
     *
     * <p>The history is carried through unchanged and the retrieved material is attached to the <em>last</em>
     * user message only, so the model sees the conversation as it happened and the quoted material where the
     * question is - attaching passages to every historical turn would multiply the untrusted text and dilute
     * the current question.
     *
     * @param command call parameters
     * @param nodes   retrieved material
     * @return chronological messages
     */
    private List<ChatMessage> promptMessages(KnowledgeCallCommand command, List<RetrievalNodeView> nodes) {
        List<String> passages = new ArrayList<>();
        for (RetrievalNodeView node : nodes) {
            passages.add(node.getContent());
        }
        List<ChatMessage> messages = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(command.getMessages())) {
            messages.addAll(command.getMessages());
        }
        messages.add(ChatMessage.user(chatPromptAssembler.userPrompt(command.getQuery(), passages)));
        return messages;
    }

    private KnowledgeCallResult withAnswer(KnowledgeCallResult result, String answer) {
        return KnowledgeCallResult.builder()
                .nodes(result.getNodes())
                .answer(answer)
                .degraded(result.getDegraded())
                .applied(result.getApplied())
                .appVersionId(result.getAppVersionId())
                .appVersion(result.getAppVersion())
                .targetStage(result.getTargetStage())
                .build();
    }

    /**
     * Override keys of a call that are not on the white list.
     *
     * @param command call parameters
     * @return keys the policy has to reject, empty when the call only used white listed ones
     */
    private Set<String> forbiddenKeysOf(KnowledgeCallCommand command) {
        Set<String> presented = command.getPresentedOverrideKeys();
        if (CollectionUtils.isEmpty(presented)) {
            return Set.of();
        }
        Set<String> forbidden = new LinkedHashSet<>(presented);
        forbidden.removeAll(requestOverridePolicy.whitelist());
        return forbidden;
    }

    private void audit(ApiKeyPrincipal principal, KnowledgeCallCommand command, ResolvedTarget target,
                       KnowledgeCallResult result, long startedAt, String endpoint) {
        if (principal == null) {
            // The console preview has no API key and is covered by the console's own login audit; recording it
            // here would put management traffic into the statistics of external call volume.
            return;
        }
        apiAuditService.recordAsync(ApiAuditService.AuditRecord.builder()
                .keyId(principal.getKeyId())
                .appId(command.getAppId())
                .appVersionId(target == null ? null : target.version().getAppVersionId())
                .targetStage(target == null ? null : target.stage())
                .endpoint(endpoint)
                .query(command.getQuery())
                .hitDocIds(result == null ? List.of() : docIdsOf(result.getNodes()))
                .latencyMs((int) (System.currentTimeMillis() - startedAt))
                .degraded(result == null ? List.of() : result.getDegraded())
                .overrideKeys(requestOverridePolicy.appliedKeys(command.getPresentedOverrideKeys()))
                .requestId(RequestIdHolder.get())
                .build());
    }

    private void auditRejection(ApiKeyPrincipal principal, KnowledgeCallCommand command, long startedAt,
                                BizException e, String endpoint) {
        if (principal == null) {
            return;
        }
        apiAuditService.recordAsync(ApiAuditService.AuditRecord.builder()
                .keyId(principal.getKeyId())
                .appId(command.getAppId())
                .endpoint(endpoint)
                .query(command.getQuery())
                .latencyMs((int) (System.currentTimeMillis() - startedAt))
                .overrideKeys(requestOverridePolicy.appliedKeys(command.getPresentedOverrideKeys()))
                .errorCode(e.getErrorCode().name())
                .requestId(RequestIdHolder.get())
                .build());
    }

    /**
     * The open API insight recording point, the M10 contract section 2.2.
     *
     * <p>Sits after a successful retrieval only: a rejected call never searched anything, so it belongs
     * to the audit trail and not to the content gap report. The enriched query is what gets digested -
     * the same choice the audit row makes - because it is the text the ranking actually came from.
     * Chat calls record the counts of their retrieval stage; the generated answer changes nothing about
     * whether the corpus could serve the question.
     *
     * <p>Also the open API sampling point of the M13 search metric, which shares the insight's boundary
     * reasoning and adds what the insight row does not carry: the wall time since the call entered. For a
     * chat call that is the retrieval stage only, since this runs before the generation starts.
     *
     * @param call      call parameters with the image text folded into the query
     * @param result    retrieval outcome of the call
     * @param startedAt epoch milliseconds the call entered the service at
     */
    private void insight(EnrichedCall call, KnowledgeCallResult result, long startedAt) {
        List<RetrievalNodeView> nodes = result.getNodes();
        int resultCount = CollectionUtils.isEmpty(nodes) ? 0 : nodes.size();
        kbMetrics.recordSearch(InsightSource.OPEN_API, System.currentTimeMillis() - startedAt,
                resultCount, result.getDegraded());
        searchInsightService.recordAsync(SearchInsightService.InsightRecord.builder()
                .source(InsightSource.OPEN_API)
                .kbIds(result.routedKbIds())
                .query(call.command().getQuery())
                .resultCount(resultCount)
                .topScore(CollectionUtils.isEmpty(nodes) ? null : nodes.get(0).getScore())
                .degraded(result.getDegraded())
                .requestId(RequestIdHolder.get())
                .build());
    }

    private List<String> docIdsOf(List<RetrievalNodeView> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return List.of();
        }
        Set<String> docIds = new LinkedHashSet<>();
        for (RetrievalNodeView node : nodes) {
            if (node.getDocId() != null) {
                docIds.add(node.getDocId());
            }
        }
        return List.copyOf(docIds);
    }

    /**
     * The application version one call resolved to, with its parsed snapshot and stage.
     *
     * @param version       version row
     * @param snapshot      parsed configuration snapshot
     * @param stage         stage the version is in, the audit dimension
     * @param snapshotBound {@code true} when the retrieval must read this version's frozen index snapshot
     *                      instead of the live aliases
     */
    private record ResolvedTarget(AppVersion version, AppConfigSnapshot snapshot, TargetStage stage,
                                  boolean snapshotBound) {
    }

    /**
     * One call after the image stage, carrying the question every later stage works on.
     *
     * <p>Exists so the audit row digests the question that was actually searched with rather than the raw
     * one: an operator reading the trail has to see the text the ranking came from, and an image only call
     * would otherwise store an empty digest.
     *
     * @param command      call parameters with the image text folded into the query
     * @param imageDegraded degradation markers the image stage produced, empty on success
     */
    private record EnrichedCall(KnowledgeCallCommand command, List<String> imageDegraded) {

        /**
         * The call before the image stage ran, used while auditing a rejection that happened earlier.
         *
         * @param command call parameters as the caller expressed them
         * @return untouched call
         */
        static EnrichedCall of(KnowledgeCallCommand command) {
            return new EnrichedCall(command, List.of());
        }
    }
}
