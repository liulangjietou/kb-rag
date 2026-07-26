package io.kbrag.app.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.kbrag.app.alert.TaskFailureTracker;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.config.AsyncConfig;
import io.kbrag.app.eval.EvalCaseStalenessService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.entity.ImageAsset;
import io.kbrag.domain.entity.KbTask;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.enums.TaskStatus;
import io.kbrag.domain.enums.TaskType;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.mapper.KbTaskMapper;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParentChunk;
import io.kbrag.domain.model.ParsePreview;
import io.kbrag.domain.model.ParsedDocument;
import io.kbrag.domain.model.ProxiedContent;
import io.kbrag.domain.model.SplitChunk;
import io.kbrag.domain.model.SplitParams;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.port.VisionProvider;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.DocumentCleaner;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.ImageChunkLinker;
import io.kbrag.domain.service.ImagePlaceholderResolver;
import io.kbrag.domain.service.ParentChildSplitter;
import io.kbrag.domain.service.SplitterRouter;
import io.kbrag.domain.service.VersionFingerprintFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexing pipeline: parse, describe images, clean, split, persist, index and activate.
 *
 * <p>MySQL is the fact source and the search engines are derived, so the order matters: chunks are
 * committed before any engine write and the version is only activated once every target accepted its
 * documents. A rerun therefore starts by deleting the chunks of the version from MySQL and from every
 * engine, which makes the whole pipeline idempotent per version.
 *
 * <p><b>Stage order.</b> Parsing produces text with {@code [[IMAGE:id]]} markers. The image stage stores
 * every binary and asks the vision model for a textual proxy. Cleaning then runs on the marked text and
 * on each proxy separately, and only afterwards are the proxies spliced into the markers. Cleaning before
 * the splice is what keeps the recorded proxy offsets exact — a substitution followed by a cleaning pass
 * would move every offset it touches and the image to chunk link would have to be guessed. The observable
 * result is the one the contract asks for: proxies sit where their image sat, and both the prose and the
 * proxies are cleaned.
 *
 * <p>Zero key deployments skip the embedding stage entirely: chunks are stored with
 * {@code embedding_status=SKIPPED} and only the full text target is written, which is what lets the
 * service run end to end without any model credential. A missing vision credential behaves the same way
 * one level down: images are stored, carry no text, and the document is indexed without them.
 *
 * <p><b>Two level splitting.</b> When the knowledge base asks for it, the document is cut twice and
 * both levels are persisted, but only the children are indexed. Parents exist purely to be returned:
 * indexing them too would make the same text match twice and would force the fusion stage to compare
 * scores computed over passages of very different length.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexPipelineService {

    private static final String PARSED_OBJECT_TEMPLATE = "kb/%s/doc/%s/%s/parsed.json";
    private static final String PREVIEW_OBJECT_TEMPLATE = "kb/%s/doc/%s/%s/preview.json";
    private static final String LEGACY_MARKDOWN_SUFFIX = ".md";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final int ENABLED = 1;
    private static final int PROGRESS_PARSED = 30;
    private static final int PROGRESS_CHUNKED = 60;
    private static final int PROGRESS_INDEXED = 90;
    private static final int PROGRESS_DONE = 100;
    private static final int NOT_STALE = 0;
    private static final int DELETE_BATCH_SIZE = 500;
    private static final int FAIL_REASON_MAX_LENGTH = 1024;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final KbTaskMapper kbTaskMapper;
    private final ObjectStorage objectStorage;
    private final DocumentParserClient parserClient;
    private final SplitterRouter splitterRouter;
    private final ParentChildSplitter parentChildSplitter;
    private final ChunkTextHasher chunkTextHasher;
    private final EmbeddingProvider embeddingProvider;
    private final ChunkEmbedder chunkEmbedder;
    private final VisionProvider visionProvider;
    private final ChunkIndexWriter chunkIndexWriter;
    private final KnowledgeBaseService knowledgeBaseService;
    private final BizIdGenerator bizIdGenerator;
    private final VersionFingerprintFactory fingerprintFactory;
    private final ImageAssetService imageAssetService;
    private final DocumentCleaner documentCleaner;
    private final ImagePlaceholderResolver placeholderResolver;
    private final ImageChunkLinker imageChunkLinker;
    private final DocumentVersionActivator versionActivator;
    private final VersionArtifactReuser versionArtifactReuser;
    private final VersionRetentionService versionRetentionService;
    private final AnnotationInheritanceService annotationInheritanceService;
    private final TaskFailureTracker taskFailureTracker;
    private final EvalCaseStalenessService evalCaseStalenessService;

    /**
     * Queues a document version for indexing.
     *
     * @param versionId document version business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submit(String versionId) {
        execute(versionId, DocumentVersionPlanner.Reuse.none());
    }

    /**
     * Queues a document version for indexing, taking over what an earlier build already produced.
     *
     * @param versionId document version business id
     * @param reuse     artifacts the version may take over
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submit(String versionId, DocumentVersionPlanner.Reuse reuse) {
        execute(versionId, reuse);
    }

    /**
     * Queues the rebuild of a version that lost its chunks, and activates it once it is complete.
     *
     * @param versionId document version business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submitRestore(String versionId) {
        restore(versionId);
    }

    /**
     * Queues a document version for a rebuild under the current knowledge base configuration.
     *
     * @param versionId document version business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submitRebuild(String versionId) {
        rebuild(versionId);
    }

    /**
     * Queues the continuation of a document that was waiting for a confirmation.
     *
     * @param versionId document version business id
     */
    @Async(AsyncConfig.INDEX_EXECUTOR)
    public void submitConfirm(String versionId) {
        confirm(versionId);
    }

    /**
     * Runs the pipeline synchronously; every failure is translated into a persisted state instead of
     * propagating, because the caller is an executor thread with nobody to report to.
     *
     * @param versionId document version business id
     */
    public void execute(String versionId) {
        execute(versionId, DocumentVersionPlanner.Reuse.none());
    }

    /**
     * Runs the pipeline synchronously, taking over what an earlier build of the same document produced.
     *
     * @param versionId document version business id
     * @param reuse     artifacts the version may take over
     */
    public void execute(String versionId, DocumentVersionPlanner.Reuse reuse) {
        DocumentVersion version = requireVersion(versionId);
        Document document = requireDocument(version.getDocId());
        KbTask task = startTask(versionId, TaskType.INDEX);
        try {
            KbIndexConfig config = knowledgeBaseService.indexConfigOf(document.getKbId());
            if (reuse.reusesChunks() && indexReusedChunks(document, version, config, reuse, task)) {
                log.info("index pipeline finished from a reused chunk generation, docId={}, versionId={}",
                        document.getDocId(), versionId);
                return;
            }
            StagedContent staged = prepare(document, version, config, null, task, adoptParsed(version, reuse));
            if (config.isParsePreviewRequired()) {
                pauseForConfirmation(document, version, config, staged, null);
                completeTask(task);
                return;
            }
            indexStaged(document, version, config, staged, task);
            log.info("index pipeline finished, docId={}, versionId={}", document.getDocId(), versionId);
        } catch (BizException e) {
            ProcessStatus failed = e.getErrorCode() == ErrorCode.PARSE_FAILED
                    ? ProcessStatus.PARSE_FAILED
                    : ProcessStatus.INDEX_FAILED;
            fail(document, version, task, failed, e.getMessage());
            log.error("index pipeline failed, errorCode={}, docId={}, versionId={}",
                    e.getErrorCode(), document.getDocId(), versionId, e);
        } catch (Exception e) {
            fail(document, version, task, ProcessStatus.INDEX_FAILED, e.getMessage());
            log.error("index pipeline failed, errorCode={}, docId={}, versionId={}",
                    ErrorCode.INTERNAL_ERROR, document.getDocId(), versionId, e);
        }
    }

    /**
     * Continues a document that was waiting for a confirmation.
     *
     * <p>The stored preview is split as it is. Re-deriving the text here would let a configuration change
     * made after the preview was rendered alter what actually reaches the index, and the operator would
     * have approved something else than what was stored.
     *
     * @param versionId document version business id
     */
    public void confirm(String versionId) {
        DocumentVersion version = requireVersion(versionId);
        Document document = requireDocument(version.getDocId());
        KbTask task = startTask(versionId, TaskType.INDEX);
        try {
            KbIndexConfig config = knowledgeBaseService.indexConfigOf(document.getKbId());
            ParsePreview preview = readPreview(document, version);
            StagedContent staged = new StagedContent(preview.toProxiedContent(),
                    preview.warningsOrEmpty(), preview.getCleanRulesOverride());
            indexStaged(document, version, config, staged, task);
            log.info("confirmed document indexed, docId={}, versionId={}", document.getDocId(), versionId);
        } catch (Exception e) {
            fail(document, version, task, ProcessStatus.INDEX_FAILED, e.getMessage());
            log.error("confirm pipeline failed, errorCode={}, docId={}, versionId={}",
                    ErrorCode.INTERNAL_ERROR, document.getDocId(), versionId, e);
        }
    }

    /**
     * Re-renders the preview of a document, optionally under an experimental rule set.
     *
     * <p>Synchronous on purpose: the console is waiting for the new preview, and the parse artifact and
     * the image proxies are both reused, so nothing expensive happens.
     *
     * @param versionId document version business id
     * @param override  cleaning rules to try, {@code null} keeps the knowledge base ones
     * @return preview that was stored
     */
    public ParsePreview reparse(String versionId, CleanRules override) {
        DocumentVersion version = requireVersion(versionId);
        Document document = requireDocument(version.getDocId());
        KbTask task = startTask(versionId, TaskType.PARSE);
        try {
            KbIndexConfig config = knowledgeBaseService.indexConfigOf(document.getKbId());
            StagedContent staged = prepare(document, version, config, override, task, true);
            ParsePreview preview = pauseForConfirmation(document, version, config, staged, override);
            completeTask(task);
            return preview;
        } catch (Exception e) {
            fail(document, version, task, ProcessStatus.PARSE_FAILED, e.getMessage());
            log.error("reparse failed, errorCode={}, docId={}, versionId={}",
                    ErrorCode.PARSE_FAILED, document.getDocId(), versionId, e);
            throw e;
        }
    }

    /**
     * Rebuilds the active version of a document under the current knowledge base configuration.
     *
     * <p>The parse artifact and the image proxies are reused whenever they are still available, because a
     * configuration change affects how the text is cut and cleaned, not how it was extracted nor what a
     * vision model saw; re-running those stages would be the expensive part of the pipeline and would
     * produce the same output.
     *
     * <p><b>Replacement order: write the new chunks first, delete the old ones second.</b> The window
     * where both exist can return a duplicate passage; the opposite order would open a window where
     * the document is not searchable at all. A duplicate is a cosmetic defect for a few seconds, a
     * hole is a wrong answer, so the overlap is the deliberate choice.
     *
     * @param versionId document version business id
     */
    public void rebuild(String versionId) {
        rebuild(versionId, false);
    }

    /**
     * Rebuilds an archived version from its stored parse artifact and makes it the active one.
     *
     * <p>This is the expensive half of a rollback. The retention policy deleted the chunks of the target
     * while deliberately keeping its original file and its parse artifact, so the content can be produced
     * again; the version status walks BUILDING and ends at ACTIVE, or at BUILD_FAILED if it does not get
     * there, which is the signal the console polls. The document pointer only moves once the chunks are
     * searchable, so a failure leaves the currently active version serving untouched.
     *
     * @param versionId document version business id
     */
    public void restore(String versionId) {
        rebuild(versionId, true);
    }

    /**
     * Rebuilds the chunk generation of a version, optionally activating it afterwards.
     *
     * @param versionId document version business id
     * @param activate  {@code true} makes the rebuilt version the active one
     */
    private void rebuild(String versionId, boolean activate) {
        DocumentVersion version = requireVersion(versionId);
        Document document = requireDocument(version.getDocId());
        KbTask task = startTask(versionId, TaskType.REBUILD);
        try {
            if (activate) {
                version.setStatus(DocumentVersionStatus.BUILDING);
                documentVersionMapper.updateById(version);
            }
            KbIndexConfig config = knowledgeBaseService.indexConfigOf(document.getKbId());
            StagedContent staged = prepare(document, version, config, null, task, true);
            updateProcessStatus(document, ProcessStatus.INDEXING, null);

            List<String> obsolete = chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                            .eq(Chunk::getDocumentVersionId, versionId))
                    .stream().map(Chunk::getChunkId).toList();

            List<Chunk> chunks = split(document, version, staged, config, task);
            index(document, chunks, task);
            removeObsolete(document.getKbId(), versionId, obsolete);

            if (activate) {
                activateAndFollowUp(document, version);
            } else {
                documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                        .set(Document::getProcessStatus, ProcessStatus.INDEXED.name())
                        .set(Document::getConfigStale, NOT_STALE)
                        .set(Document::getFailReason, null)
                        .eq(Document::getDocId, document.getDocId()));
            }
            completeTask(task);
            log.info("rebuild finished, docId={}, versionId={}, newChunks={}, removedChunks={}, activated={}",
                    document.getDocId(), versionId, chunks.size(), obsolete.size(), activate);
        } catch (Exception e) {
            fail(document, version, task, ProcessStatus.INDEX_FAILED, e.getMessage());
            log.error("rebuild failed, errorCode={}, docId={}, versionId={}",
                    ErrorCode.INTERNAL_ERROR, document.getDocId(), versionId, e);
        }
    }

    /**
     * Adopts the parse artifact of an earlier build so the parser is not called again.
     *
     * @param version version being built
     * @param reuse   reuse decision of the intake
     * @return {@code true} when the stored artifact may be read instead of parsing
     */
    private boolean adoptParsed(DocumentVersion version, DocumentVersionPlanner.Reuse reuse) {
        if (!reuse.reusesParse()) {
            return false;
        }
        version.setParsedObject(reuse.parsedObject());
        documentVersionMapper.updateById(version);
        log.info("parse artifact reused, versionId={}, sourceVersionId={}, object={}",
                version.getVersionId(), reuse.sourceVersionId(), reuse.parsedObject());
        return true;
    }

    /**
     * Builds a version by copying the chunk generation of an earlier one.
     *
     * <p>Reports failure rather than throwing when the source turns out to be empty, so the caller falls
     * back to a full build: the source may have been archived between the intake decision and this call,
     * and a document that silently ends up with no chunk would be far worse than a redundant parse.
     *
     * @param document document record
     * @param version  version being built
     * @param config   knowledge base index configuration
     * @param reuse    reuse decision of the intake
     * @param task     running task
     * @return {@code true} when the version was built and activated from the copy
     */
    private boolean indexReusedChunks(Document document, DocumentVersion version, KbIndexConfig config,
                                      DocumentVersionPlanner.Reuse reuse, KbTask task) {
        updateProcessStatus(document, ProcessStatus.INDEXING, null);
        deleteExistingChunks(document.getKbId(), version.getVersionId());
        List<Chunk> copied = versionArtifactReuser.copyChunks(document, version, reuse.sourceVersionId(),
                embeddingProvider.isConfigured());
        if (CollectionUtils.isEmpty(copied)) {
            log.info("chunk reuse source carries nothing, falling back to a full build, "
                    + "versionId={}, sourceVersionId={}", version.getVersionId(), reuse.sourceVersionId());
            return false;
        }
        version.setParsedObject(reuse.parsedObject());
        version.setParseFingerprint(fingerprintFactory.parseFingerprint(config, visionProvider.model()));
        version.setChunkFingerprint(fingerprintFactory.chunkFingerprint(config));
        version.setEmbeddingVersion(embeddingProvider.model());
        documentVersionMapper.updateById(version);
        updateProgress(task, PROGRESS_CHUNKED);
        index(document, copied, task);
        activateAndFollowUp(document, version);
        completeTask(task);
        return true;
    }

    /**
     * Makes a freshly built version the active one and runs everything that depends on that.
     *
     * <p>Both follow ups belong here rather than inside the activator: the activator is also used by the
     * chat import path and by a manual rollback, which need the switch without necessarily needing the
     * same follow ups, and the single active version invariant must not depend on either of them
     * succeeding.
     *
     * @param document document record
     * @param version  version that finished building
     */
    private void activateAndFollowUp(Document document, DocumentVersion version) {
        versionActivator.activate(document, version);
        annotationInheritanceService.inherit(document, version);
        evalCaseStalenessService.markStale(document.getDocId(), version.getVersionId());
        versionRetentionService.submit(document.getDocId());
    }

    /**
     * Runs everything up to the point where the text is ready to be cut.
     *
     * @param document    document record
     * @param version     version being built
     * @param config      knowledge base index configuration
     * @param override    experimental cleaning rules, {@code null} keeps the knowledge base ones
     * @param task        running task
     * @param reuseParsed {@code true} reads the stored parse artifact instead of calling the parser
     * @return text to split and the image placements inside it
     */
    private StagedContent prepare(Document document, DocumentVersion version, KbIndexConfig config,
                                  CleanRules override, KbTask task, boolean reuseParsed) {
        ParsedDocument parsed = reuseParsed
                ? readParsed(document, version, task)
                : parse(document, version, config, task);
        List<String> warnings = new ArrayList<>(parsed.warningsOrEmpty());

        List<ImageAsset> assets = imageAssetService.isStandaloneImage(document.getFileExt())
                ? List.of(imageAssetService.materializeStandalone(document, version,
                        readAll(objectStorage.get(version.getMinioObject()))))
                : imageAssetService.materialize(document, version, parsed, warnings);

        CleanRules rules = override == null ? config.cleanRulesOrDefaults() : override;
        String markdown = markdownFor(document, parsed, assets);
        String cleaned = documentCleaner.clean(markdown, parsed.pagesOrEmpty(), rules);

        Map<String, ImagePlaceholderResolver.ProxyTarget> proxies = new LinkedHashMap<>();
        for (ImageAsset asset : assets) {
            if (!asset.hasTextProxy()) {
                continue;
            }
            proxies.put(asset.getSourceImageId(), new ImagePlaceholderResolver.ProxyTarget(
                    asset.getImageId(), asset.getObjectKey(),
                    documentCleaner.cleanFragment(asset.getTextProxy(), rules)));
        }
        ProxiedContent proxied = withStandaloneFallback(document, assets,
                placeholderResolver.resolve(cleaned, proxies));

        version.setParseFingerprint(fingerprintFactory.parseFingerprint(configWithRules(config, rules),
                visionProvider.model()));
        documentVersionMapper.updateById(version);
        return new StagedContent(proxied, warnings, override);
    }

    /**
     * Guarantees that an uploaded image is always attached to its chunk.
     *
     * <p>The placement list normally comes from the substitution, which drops a placeholder whose vision call
     * was skipped or failed. For an embedded figure that is right: with no description there is no text to
     * attach the image to. A standalone upload is the opposite case — the document <em>is</em> the image, the
     * association is unambiguous, and the console has to be able to show the picture even in a deployment
     * without a vision credential. The whole text is therefore claimed by that one asset.
     *
     * @param document document record
     * @param assets   image assets of this version
     * @param proxied  substitution result
     * @return substitution result carrying at least one placement for a standalone image
     */
    private ProxiedContent withStandaloneFallback(Document document, List<ImageAsset> assets,
                                                  ProxiedContent proxied) {
        if (!imageAssetService.isStandaloneImage(document.getFileExt())
                || CollectionUtils.isEmpty(assets)
                || CollectionUtils.isNotEmpty(proxied.getPlacements())) {
            return proxied;
        }
        ImageAsset asset = assets.get(0);
        return new ProxiedContent(proxied.getMarkdown(), List.of(new ProxiedContent.ImagePlacement(
                asset.getImageId(), asset.getObjectKey(), 0, Math.max(1, proxied.getMarkdown().length()))));
    }

    /**
     * Builds the text the cleaning stage receives.
     *
     * <p>A standalone image upload has no text at all, so the file name becomes its context and the
     * synthesised placeholder is what the resolver replaces with the description. Without the file name a
     * chunk consisting only of a model description would carry no clue about which document it came from.
     *
     * @param document document record
     * @param parsed   parse result
     * @param assets   image assets of this version
     * @return markdown carrying the image placeholders
     */
    private String markdownFor(Document document, ParsedDocument parsed, List<ImageAsset> assets) {
        if (!imageAssetService.isStandaloneImage(document.getFileExt()) || CollectionUtils.isEmpty(assets)) {
            return parsed.markdownOrEmpty();
        }
        return document.getFileName() + "\n"
                + placeholderResolver.placeholderOf(assets.get(0).getSourceImageId());
    }

    /**
     * Copies the configuration with a different rule set, so a re-parse can be fingerprinted without
     * mutating the stored knowledge base configuration.
     *
     * @param config knowledge base index configuration
     * @param rules  rules actually applied
     * @return configuration carrying those rules
     */
    private KbIndexConfig configWithRules(KbIndexConfig config, CleanRules rules) {
        if (rules == config.cleanRulesOrDefaults()) {
            return config;
        }
        KbIndexConfig copy = JsonUtil.parse(JsonUtil.toJson(config), KbIndexConfig.class);
        copy.setCleanRules(rules);
        return copy;
    }

    /**
     * Splits, indexes and activates a staged document.
     *
     * @param document document record
     * @param version  version being built
     * @param config   knowledge base index configuration
     * @param staged   text to cut
     * @param task     running task
     */
    private void indexStaged(Document document, DocumentVersion version, KbIndexConfig config,
                             StagedContent staged, KbTask task) {
        updateProcessStatus(document, ProcessStatus.INDEXING, null);
        deleteExistingChunks(document.getKbId(), version.getVersionId());
        List<Chunk> chunks = split(document, version, staged, config, task);
        index(document, chunks, task);
        activateAndFollowUp(document, version);
        completeTask(task);
    }

    /**
     * Stores the preview and parks the document until a human confirms it.
     *
     * @param document document record
     * @param version  version being built
     * @param config   knowledge base index configuration
     * @param staged   text that would be indexed
     * @param override experimental cleaning rules that produced it
     * @return stored preview
     */
    private ParsePreview pauseForConfirmation(Document document, DocumentVersion version,
                                             KbIndexConfig config, StagedContent staged,
                                             CleanRules override) {
        ParsedDocument parsed = readParsedQuietly(document, version);
        List<ParsePreview.PreviewImage> images = new ArrayList<>();
        for (ImageAsset asset : imageAssetService.findByVersion(version.getVersionId())) {
            images.add(ParsePreview.PreviewImage.builder()
                    .imageId(asset.getImageId())
                    .objectKey(asset.getObjectKey())
                    .pageNo(asset.getPageNo())
                    .kind(asset.getKind() == null ? null : asset.getKind().code())
                    .textProxy(asset.getTextProxy())
                    .status(asset.getStatus() == null ? null : asset.getStatus().name())
                    .build());
        }
        List<ParsePreview.PreviewPlacement> placements = new ArrayList<>();
        for (ProxiedContent.ImagePlacement placement : staged.proxied().getPlacements()) {
            placements.add(ParsePreview.PreviewPlacement.builder()
                    .imageId(placement.getImageId())
                    .objectKey(placement.getObjectKey())
                    .start(placement.getStart())
                    .end(placement.getEnd())
                    .build());
        }
        ParsePreview preview = ParsePreview.builder()
                .markdown(staged.proxied().getMarkdown())
                .pages(parsed == null ? List.of() : parsed.pagesOrEmpty())
                .images(images)
                .placements(placements)
                .warnings(staged.warnings())
                .cleanRulesOverride(override)
                .build();
        writeObject(previewKey(document, version), JsonUtil.toJson(preview));
        version.setChunkFingerprint(fingerprintFactory.chunkFingerprint(config));
        documentVersionMapper.updateById(version);
        updateProcessStatus(document, ProcessStatus.PENDING_CONFIRM, null);
        log.info("document waiting for parse confirmation, docId={}, versionId={}, images={}",
                document.getDocId(), version.getVersionId(), images.size());
        return preview;
    }

    /**
     * Reads the stored preview of a document.
     *
     * @param document document record
     * @param version  version being confirmed
     * @return stored preview
     */
    public ParsePreview readPreview(Document document, DocumentVersion version) {
        String key = previewKey(document, version);
        ParsePreview preview = JsonUtil.parse(
                new String(readAll(objectStorage.get(key)), StandardCharsets.UTF_8), ParsePreview.class);
        if (preview == null) {
            throw BizException.notFound("parse preview not available");
        }
        return preview;
    }

    private ParsedDocument parse(Document document, DocumentVersion version, KbIndexConfig config,
                                 KbTask task) {
        updateProcessStatus(document, ProcessStatus.PARSING, null);
        ParsedDocument parsed;
        if (imageAssetService.isStandaloneImage(document.getFileExt())) {
            // The parser handles documents; an image has nothing to extract, so the stage is skipped and
            // the whole text of the document comes from the vision model.
            parsed = ParsedDocument.builder().markdown("").build();
        } else {
            byte[] raw = readAll(objectStorage.get(version.getMinioObject()));
            parsed = parserClient.parse(document.getFileName(), document.getFileExt(), raw);
        }
        writeObject(parsedKey(document, version), JsonUtil.toJson(parsed));
        version.setParsedObject(parsedKey(document, version));
        documentVersionMapper.updateById(version);
        updateProcessStatus(document, ProcessStatus.PARSED, null);
        updateProgress(task, PROGRESS_PARSED);
        return parsed;
    }

    /**
     * Reads the stored parse artifact, falling back to a fresh parse when it is gone.
     *
     * <p>Versions built before the artifact became a JSON document stored plain markdown, which is read
     * back without its page structure: the header and footer detection then finds nothing, which is the
     * correct outcome for a document whose pages were never recorded.
     *
     * @param document document record
     * @param version  version being rebuilt
     * @param task     running task
     * @return parse result
     */
    private ParsedDocument readParsed(Document document, DocumentVersion version, KbTask task) {
        ParsedDocument parsed = readParsedQuietly(document, version);
        if (parsed != null) {
            return parsed;
        }
        KbIndexConfig config = knowledgeBaseService.indexConfigOf(document.getKbId());
        return parse(document, version, config, task);
    }

    /**
     * Reads the stored parse artifact without falling back to a parser call.
     *
     * @param document document record
     * @param version  version being read
     * @return parse result, {@code null} when no artifact is readable
     */
    private ParsedDocument readParsedQuietly(Document document, DocumentVersion version) {
        String key = version.getParsedObject();
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            String body = new String(readAll(objectStorage.get(key)), StandardCharsets.UTF_8);
            if (key.endsWith(LEGACY_MARKDOWN_SUFFIX)) {
                return ParsedDocument.builder().markdown(body).build();
            }
            return JsonUtil.parse(body, ParsedDocument.class);
        } catch (Exception e) {
            log.info("parse artifact unreadable, versionId={}, object={}", version.getVersionId(), key);
            return null;
        }
    }

    /**
     * Cuts the text according to the knowledge base configuration and persists the chunks.
     *
     * @param document document record
     * @param version  version being built
     * @param staged   text to cut and the image placements inside it
     * @param config   knowledge base index configuration
     * @param task     running task
     * @return chunks that have to reach the engines, parents excluded
     */
    private List<Chunk> split(Document document, DocumentVersion version, StagedContent staged,
                              KbIndexConfig config, KbTask task) {
        boolean embeddingConfigured = embeddingProvider.isConfigured();
        boolean standaloneImage = imageAssetService.isStandaloneImage(document.getFileExt());
        List<Chunk> indexable = config.parentChildEnabled() && !standaloneImage
                ? splitTwoLevel(document, version, staged, config, embeddingConfigured)
                : splitSingleLevel(document, version, staged, config, embeddingConfigured, standaloneImage);

        version.setChunkFingerprint(fingerprintFactory.chunkFingerprint(config));
        version.setEmbeddingVersion(embeddingProvider.model());
        documentVersionMapper.updateById(version);
        updateProgress(task, PROGRESS_CHUNKED);
        log.info("chunks persisted, docId={}, versionId={}, indexable={}, parentChild={}, embeddingConfigured={}",
                document.getDocId(), version.getVersionId(), indexable.size(),
                config.parentChildEnabled(), embeddingConfigured);
        return indexable;
    }

    /**
     * Cuts the text into a single level and links each chunk to the images it contains.
     *
     * @param document            document record
     * @param version             version being built
     * @param staged              text to cut
     * @param config              knowledge base index configuration
     * @param embeddingConfigured {@code true} when the vector route is available
     * @param standaloneImage     {@code true} when the document is one uploaded image
     * @return persisted chunks
     */
    private List<Chunk> splitSingleLevel(Document document, DocumentVersion version, StagedContent staged,
                                         KbIndexConfig config, boolean embeddingConfigured,
                                         boolean standaloneImage) {
        List<SplitChunk> splitChunks = splitterRouter.resolve(config.getSplitStrategy())
                .split(staged.proxied().getMarkdown(), config.splitParams(cacheContextOf(document, version)));
        Map<Integer, List<String>> imagesByChunk = imageChunkLinker.link(staged.proxied().getMarkdown(),
                staged.proxied().getPlacements(), splitChunks.stream().map(SplitChunk::getContent).toList());
        List<Chunk> chunks = new ArrayList<>(splitChunks.size());
        for (int index = 0; index < splitChunks.size(); index++) {
            List<String> imageKeys = imagesByChunk.get(index);
            Chunk chunk = persistChunk(document, version, splitChunks.get(index), null, embeddingConfigured,
                    standaloneImage ? ChunkType.IMAGE : ChunkType.TEXT,
                    metadataOf(imageKeys, splitChunks.get(index)));
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * Persists both levels and returns only the children.
     *
     * <p>Parents are stored with {@code embedding_status=SKIPPED} because they are never embedded and
     * never written to an engine; marking them PENDING would make the compensation scan chase a write
     * that is not supposed to happen.
     *
     * @param document            document record
     * @param version             version being built
     * @param staged              text to cut
     * @param config              knowledge base index configuration
     * @param embeddingConfigured {@code true} when the vector route is available
     * @return child chunks
     */
    private List<Chunk> splitTwoLevel(Document document, DocumentVersion version, StagedContent staged,
                                      KbIndexConfig config, boolean embeddingConfigured) {
        List<ParentChunk> groups = parentChildSplitter.split(staged.proxied().getMarkdown(),
                config.parentChildOrDisabled());
        List<String> childContents = new ArrayList<>();
        for (ParentChunk group : groups) {
            for (SplitChunk child : group.getChildren()) {
                childContents.add(child.getContent());
            }
        }
        Map<Integer, List<String>> imagesByChunk = imageChunkLinker.link(staged.proxied().getMarkdown(),
                staged.proxied().getPlacements(), childContents);

        List<Chunk> children = new ArrayList<>();
        int childIndex = 0;
        for (ParentChunk group : groups) {
            Chunk parent = persistChunk(document, version, group.getParent(), null, false,
                    ChunkType.TEXT, null);
            for (SplitChunk child : group.getChildren()) {
                children.add(persistChunk(document, version, child, parent.getChunkId(), embeddingConfigured,
                        ChunkType.TEXT, metadataOf(imagesByChunk.get(childIndex++), child)));
            }
        }
        return children;
    }

    /**
     * Merges the image links a chunk carries with the title/summary/keywords the LLM semantic
     * splitter attaches to it, requirement section 4.3.
     *
     * @param imageKeys  object storage keys of the images this chunk contains, may be empty
     * @param splitChunk splitter output, {@code null} metadata for every strategy but the LLM one
     * @return JSON metadata document, {@code null} when there is nothing to store
     */
    private String metadataOf(List<String> imageKeys, SplitChunk splitChunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (CollectionUtils.isNotEmpty(imageKeys)) {
            metadata.put(ChunkMetadataKeys.IMAGE_URLS, imageKeys);
        }
        if (splitChunk != null && splitChunk.getMetadata() != null && !splitChunk.getMetadata().isEmpty()) {
            metadata.putAll(splitChunk.getMetadata());
        }
        return metadata.isEmpty() ? null : JsonUtil.toJson(metadata);
    }

    /**
     * Builds the LLM semantic splitter's cache coordinates for one document version.
     *
     * @param document document record
     * @param version  version being built
     * @return cache context, safe to pass to every strategy since only the LLM one reads it
     */
    private SplitParams.CacheContext cacheContextOf(Document document, DocumentVersion version) {
        return new SplitParams.CacheContext(document.getKbId(), document.getDocId(),
                version.getVersionId(), version.getContentHash());
    }

    private Chunk persistChunk(Document document, DocumentVersion version, SplitChunk splitChunk,
                               String parentId, boolean embeddingConfigured, ChunkType chunkType,
                               String metadata) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(bizIdGenerator.chunkId());
        chunk.setKbId(document.getKbId());
        chunk.setDocId(document.getDocId());
        chunk.setDocumentVersionId(version.getVersionId());
        chunk.setContent(splitChunk.getContent());
        chunk.setChunkTextHash(chunkTextHasher.hash(splitChunk.getContent()));
        chunk.setParentId(parentId);
        chunk.setSeq(splitChunk.getSeq());
        chunk.setChunkType(chunkType);
        chunk.setEnabled(ENABLED);
        chunk.setMetadata(metadata);
        chunk.setEmbeddingStatus(embeddingConfigured ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        chunkMapper.insert(chunk);
        return chunk;
    }

    private void index(Document document, List<Chunk> chunks, KbTask task) {
        if (CollectionUtils.isEmpty(chunks)) {
            updateProgress(task, PROGRESS_INDEXED);
            return;
        }
        chunkIndexWriter.write(document.getKbId(), chunks, chunkEmbedder.embed(chunks));
        updateProgress(task, PROGRESS_INDEXED);
    }

    /**
     * Removes the chunks of a version from MySQL and from every engine, making a rerun idempotent.
     *
     * @param kbId      knowledge base business id
     * @param versionId document version business id
     */
    private void deleteExistingChunks(String kbId, String versionId) {
        knowledgeBaseService.removeChunks(kbId,
                new LambdaQueryWrapper<Chunk>().eq(Chunk::getDocumentVersionId, versionId));
    }

    /**
     * Removes the chunk generation the rebuild replaced.
     *
     * <p>The identifiers are deleted in batches rather than in one predicate: a long document produces
     * thousands of chunks and a single {@code IN} list of that size is rejected outright by some MySQL
     * configurations, which would leave the rebuild with two live generations.
     *
     * @param kbId      knowledge base business id
     * @param versionId document version business id
     * @param chunkIds  chunk ids of the previous generation
     */
    private void removeObsolete(String kbId, String versionId, List<String> chunkIds) {
        if (CollectionUtils.isEmpty(chunkIds)) {
            return;
        }
        for (int start = 0; start < chunkIds.size(); start += DELETE_BATCH_SIZE) {
            List<String> batch = chunkIds.subList(start, Math.min(chunkIds.size(), start + DELETE_BATCH_SIZE));
            knowledgeBaseService.removeChunks(kbId, new LambdaQueryWrapper<Chunk>()
                    .eq(Chunk::getDocumentVersionId, versionId)
                    .in(Chunk::getChunkId, batch));
        }
    }

    private KbTask startTask(String versionId, TaskType taskType) {
        KbTask task = kbTaskMapper.selectOne(new LambdaQueryWrapper<KbTask>()
                .eq(KbTask::getBizId, versionId)
                .eq(KbTask::getTaskType, taskType)
                .orderByDesc(KbTask::getId)
                .last("limit 1"));
        if (task == null) {
            task = new KbTask();
            task.setTaskId(bizIdGenerator.taskId());
            task.setTaskType(taskType);
            task.setBizId(versionId);
            task.setRetryCount(0);
            task.setStatus(TaskStatus.RUNNING);
            task.setProgress(0);
            kbTaskMapper.insert(task);
            return task;
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setProgress(0);
        task.setFailReason(null);
        task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
        kbTaskMapper.update(null, new LambdaUpdateWrapper<KbTask>()
                .set(KbTask::getStatus, TaskStatus.RUNNING.name())
                .set(KbTask::getProgress, 0)
                .set(KbTask::getFailReason, null)
                .set(KbTask::getRetryCount, task.getRetryCount())
                .eq(KbTask::getTaskId, task.getTaskId()));
        return task;
    }

    private void updateProgress(KbTask task, int progress) {
        task.setProgress(progress);
        kbTaskMapper.updateById(task);
    }

    private void completeTask(KbTask task) {
        task.setStatus(TaskStatus.SUCCESS);
        task.setProgress(PROGRESS_DONE);
        kbTaskMapper.updateById(task);
        taskFailureTracker.recordSuccess(task.getTaskType());
    }

    private void fail(Document document, DocumentVersion version, KbTask task,
                      ProcessStatus status, String reason) {
        String safeReason = truncate(reason);
        updateProcessStatus(document, status, safeReason);
        version.setStatus(DocumentVersionStatus.BUILD_FAILED);
        documentVersionMapper.updateById(version);
        task.setStatus(TaskStatus.FAILED);
        task.setFailReason(safeReason);
        kbTaskMapper.updateById(task);
        taskFailureTracker.recordFailure(task.getTaskType(), safeReason);
    }

    /**
     * Persists the processing state of a document.
     *
     * <p>An explicit update is used instead of {@code updateById} because a successful rerun has to
     * clear {@code fail_reason}, and the entity based update silently skips null values.
     *
     * @param document   document record, kept in sync in memory
     * @param status     new processing state
     * @param failReason classified failure cause, {@code null} clears a previous one
     */
    private void updateProcessStatus(Document document, ProcessStatus status, String failReason) {
        document.setProcessStatus(status);
        document.setFailReason(failReason);
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .set(Document::getProcessStatus, status.name())
                .set(Document::getFailReason, failReason)
                .set(Document::getCurrentVersionId, document.getCurrentVersionId())
                .eq(Document::getDocId, document.getDocId()));
    }

    private DocumentVersion requireVersion(String versionId) {
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getVersionId, versionId)
                .last("limit 1"));
        if (version == null) {
            throw BizException.notFound("document version not found");
        }
        return version;
    }

    private Document requireDocument(String docId) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("document not found");
        }
        return document;
    }

    private String parsedKey(Document document, DocumentVersion version) {
        return String.format(PARSED_OBJECT_TEMPLATE, document.getKbId(), document.getDocId(),
                version.getVersionId());
    }

    private String previewKey(Document document, DocumentVersion version) {
        return String.format(PREVIEW_OBJECT_TEMPLATE, document.getKbId(), document.getDocId(),
                version.getVersionId());
    }

    private void writeObject(String objectKey, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        objectStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, CONTENT_TYPE_JSON);
    }

    private byte[] readAll(InputStream stream) {
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (Exception e) {
            log.error("read object failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "read stored object failed", e);
        }
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > FAIL_REASON_MAX_LENGTH ? reason.substring(0, FAIL_REASON_MAX_LENGTH) : reason;
    }

    /**
     * Text ready to be cut, together with what produced it.
     *
     * @param proxied  cleaned text and the image placements inside it
     * @param warnings non fatal findings surfaced in the preview
     * @param override experimental cleaning rules that produced it, {@code null} for the stored ones
     */
    private record StagedContent(ProxiedContent proxied, List<String> warnings, CleanRules override) {
    }
}
