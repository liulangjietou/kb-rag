package io.kbrag.app.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.index.ChunkEmbedder;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.index.DocumentVersionActivator;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.constant.ChunkMetadataKeys;
import io.kbrag.domain.entity.Chunk;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.DocumentVersion;
import io.kbrag.domain.enums.ChunkType;
import io.kbrag.domain.enums.DocumentVersionStatus;
import io.kbrag.domain.enums.EmbeddingStatus;
import io.kbrag.domain.enums.ProcessStatus;
import io.kbrag.domain.mapper.ChunkMapper;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.DocumentVersionMapper;
import io.kbrag.domain.model.ChatWindow;
import io.kbrag.domain.model.CleanRules;
import io.kbrag.domain.model.KbIndexConfig;
import io.kbrag.domain.model.ParsedChatFile;
import io.kbrag.domain.port.DocumentParserClient;
import io.kbrag.domain.port.EmbeddingProvider;
import io.kbrag.domain.port.ObjectStorage;
import io.kbrag.domain.service.BizIdGenerator;
import io.kbrag.domain.service.ChatWindowAggregator;
import io.kbrag.domain.service.ChunkTextHasher;
import io.kbrag.domain.service.DocumentCleaner;
import io.kbrag.domain.service.DocumentVersionPlanner;
import io.kbrag.domain.service.VersionFingerprintFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Two step import of a chat export.
 *
 * <p>Step one parses the file, stages the result and returns what the import would do. Step two executes
 * the decision the user saw. Splitting the flow is what makes the version semantics safe: the user finds
 * out that three of five conversations already exist before anything is written, instead of discovering
 * duplicated documents afterwards.
 *
 * <p><b>A conversation is a document, not a file.</b> One export may hold many conversations and becomes
 * many documents; the same conversation exported twice becomes two versions of one document, replacing its
 * content in full. That is the requirement, and it is why the source key rather than the file name carries
 * the identity.
 *
 * <p><b>Masking is on by default here.</b> A chat log is personal data whether or not the knowledge base
 * enabled masking for its documents, so the chat path forces the master switch on and only takes the per
 * category switches from the knowledge base rules.
 *
 * <p>The windows are indexed as {@code chunk_type=chat_log} carrying the session, sender and time
 * metadata, which are exactly the engine side fields the retrieval metadata filter queries.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatImportService {

    private static final String STAGED_OBJECT_TEMPLATE = "kb/%s/chat-import/%s/parsed.json";
    private static final String SOURCE_OBJECT_TEMPLATE = "kb/%s/chat-import/%s/source.%s";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    private static final List<String> SUPPORTED_EXTENSIONS = List.of("csv", "xlsx");
    private static final String CHAT_FILE_EXT = "chat";
    private static final int ENABLED = 1;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final ObjectStorage objectStorage;
    private final DocumentParserClient parserClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatUploadTokenStore uploadTokenStore;
    private final ChatSessionMatcher sessionMatcher;
    private final ChatWindowAggregator windowAggregator;
    private final ChatWindowRenderer windowRenderer;
    private final DocumentCleaner documentCleaner;
    private final ChunkTextHasher chunkTextHasher;
    private final ChunkIndexWriter chunkIndexWriter;
    private final ChunkEmbedder chunkEmbedder;
    private final DocumentVersionActivator versionActivator;
    private final AnnotationInheritanceService annotationInheritanceService;
    private final EmbeddingProvider embeddingProvider;
    private final BizIdGenerator bizIdGenerator;
    private final VersionFingerprintFactory fingerprintFactory;
    private final DocumentVersionPlanner versionPlanner;
    private final KbProperties properties;

    /**
     * Parses an export, stages it and reports the import plan.
     *
     * @param kbId           target knowledge base
     * @param fileName       original file name
     * @param content        raw file bytes
     * @param mappingProfile column mapping profile, {@code null} selects the configured default
     * @return match preview carrying the upload token the confirmation needs
     */
    public ChatImportView preview(String kbId, String fileName, byte[] content, String mappingProfile) {
        knowledgeBaseService.require(kbId);
        String extension = extensionOf(fileName);
        String profile = mappingProfile == null || mappingProfile.isBlank()
                ? properties.getChatImport().getDefaultMappingProfile() : mappingProfile;

        ParsedChatFile parsed = parserClient.parseChat(fileName, extension, content, profile);
        if (CollectionUtils.isEmpty(parsed.sessionsOrEmpty())) {
            throw BizException.invalidParam("the export carries no recognisable conversation");
        }
        String token = bizIdGenerator.uploadToken();
        String stagedKey = String.format(STAGED_OBJECT_TEMPLATE, kbId, token);
        writeObject(stagedKey, JsonUtil.toJson(parsed), CONTENT_TYPE_JSON);
        // The original file is staged as well: a mapping profile that turns out wrong can then be replayed
        // without asking the user to upload the export again.
        objectStorage.put(String.format(SOURCE_OBJECT_TEMPLATE, kbId, token, extension),
                new ByteArrayInputStream(content), content.length, CONTENT_TYPE_OCTET_STREAM);
        uploadTokenStore.register(token, kbId, stagedKey, fileName);

        List<ChatImportView.SessionMatch> matches =
                sessionMatcher.match(parsed.sessionsOrEmpty(), existingSourceKeys(kbId));
        log.info("chat import preview built, kbId={}, fileName={}, sessions={}, profile={}",
                kbId, fileName, matches.size(), profile);
        return ChatImportView.builder()
                .uploadToken(token)
                .sessions(matches)
                .skipped(parsed.skippedOrEmpty())
                .build();
    }

    /**
     * Executes a staged import.
     *
     * @param kbId       target knowledge base
     * @param token      token returned by the preview
     * @param sessionIds conversations to import, empty imports every conversation of the export
     * @return document ids that were created or given a new version
     */
    public List<String> confirm(String kbId, String token, List<String> sessionIds) {
        knowledgeBaseService.require(kbId);
        ChatUploadTokenStore.StagedUpload staged = uploadTokenStore.require(kbId, token);
        ParsedChatFile parsed = readStaged(staged.objectKey());
        Set<String> selected = CollectionUtils.isEmpty(sessionIds) ? Set.of() : new HashSet<>(sessionIds);

        KbIndexConfig config = knowledgeBaseService.indexConfigOf(kbId);
        CleanRules rules = chatCleanRules(config);
        Map<String, String> existing = existingSourceKeys(kbId);

        List<String> importedDocIds = new ArrayList<>();
        for (ParsedChatFile.ChatSession session : parsed.sessionsOrEmpty()) {
            if (!selected.isEmpty() && !selected.contains(session.getSessionId())) {
                continue;
            }
            if (CollectionUtils.isEmpty(session.messagesOrEmpty())) {
                log.info("skip empty chat session, kbId={}, sessionId={}", kbId, session.getSessionId());
                continue;
            }
            importedDocIds.add(importSession(kbId, session, config, rules,
                    existing.get(sessionMatcher.sourceKeyOf(session.getSessionId()))));
        }
        uploadTokenStore.consume(token);
        log.info("chat import confirmed, kbId={}, fileName={}, documents={}",
                kbId, staged.fileName(), importedDocIds.size());
        return importedDocIds;
    }

    /**
     * Imports one conversation as a new document or as a new version of an existing one.
     *
     * @param kbId          target knowledge base
     * @param session       conversation to import
     * @param config        knowledge base index configuration
     * @param rules         cleaning rules of the chat path
     * @param existingDocId document already carrying the conversation, {@code null} when it is new
     * @return document business id
     */
    private String importSession(String kbId, ParsedChatFile.ChatSession session, KbIndexConfig config,
                                 CleanRules rules, String existingDocId) {
        Document document = existingDocId == null
                ? createDocument(kbId, session)
                : loadDocument(existingDocId, session);
        DocumentVersion version = createVersion(document, session);

        List<ChatWindow> windows = windowAggregator.aggregate(session.messagesOrEmpty(),
                config.chatAggregationOrDefaults());
        List<Chunk> chunks = new ArrayList<>(windows.size());
        boolean embeddingConfigured = embeddingProvider.isConfigured();
        for (ChatWindow window : windows) {
            String text = documentCleaner.cleanFragment(windowRenderer.render(window), rules);
            if (text == null || text.isBlank()) {
                continue;
            }
            chunks.add(persistChunk(document, version, window, session, text, embeddingConfigured));
        }
        version.setChunkFingerprint(fingerprintFactory.chunkFingerprint(config));
        version.setParseFingerprint(fingerprintFactory.parseFingerprint(config, CHAT_FILE_EXT));
        version.setEmbeddingVersion(embeddingProvider.model());
        documentVersionMapper.updateById(version);

        if (CollectionUtils.isNotEmpty(chunks)) {
            chunkIndexWriter.write(kbId, chunks, chunkEmbedder.embed(chunks));
        }
        // Replacing the whole content is the requirement for a re-import, so the chunks of the version that
        // was active until now are removed only after the new ones are searchable.
        removePreviousChunks(kbId, document, version.getVersionId());
        versionActivator.activate(document, version);
        // A conversation re-imported under the same identity is a new version of the same document, so the
        // disable decisions taken on the previous one follow the text exactly as they do for an upload.
        annotationInheritanceService.inherit(document, version);
        log.info("chat session imported, kbId={}, docId={}, versionId={}, windows={}, chunks={}",
                kbId, document.getDocId(), version.getVersionId(), windows.size(), chunks.size());
        return document.getDocId();
    }

    private Document createDocument(String kbId, ParsedChatFile.ChatSession session) {
        Document document = new Document();
        document.setDocId(bizIdGenerator.documentId());
        document.setKbId(kbId);
        document.setFileName(sessionMatcher.displayNameOf(session));
        document.setFileExt(CHAT_FILE_EXT);
        document.setFileSize(0L);
        document.setProcessStatus(ProcessStatus.INDEXING);
        document.setConfigStale(0);
        document.setSourceKey(sessionMatcher.sourceKeyOf(session.getSessionId()));
        documentMapper.insert(document);
        return document;
    }

    /**
     * Loads the document a conversation already maps to and refreshes its display name.
     *
     * @param docId   document business id
     * @param session conversation being re-imported
     * @return document record
     */
    private Document loadDocument(String docId, ParsedChatFile.ChatSession session) {
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getDocId, docId)
                .last("limit 1"));
        if (document == null) {
            throw BizException.notFound("document not found: " + docId);
        }
        // A renamed conversation keeps its identity but should show its current name in the console.
        document.setFileName(sessionMatcher.displayNameOf(session));
        documentMapper.updateById(document);
        return document;
    }

    /**
     * Creates the version this import writes into.
     *
     * @param document document record
     * @param session  conversation being imported
     * @return persisted version row
     */
    private DocumentVersion createVersion(Document document, ParsedChatFile.ChatSession session) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(bizIdGenerator.documentVersionId());
        version.setDocId(document.getDocId());
        version.setVersion(nextVersionNumber(document.getDocId()));
        version.setContentHash(HashUtil.sha256Hex(
                JsonUtil.toJson(session.messagesOrEmpty()).getBytes(StandardCharsets.UTF_8)));
        version.setStatus(DocumentVersionStatus.BUILDING);
        documentVersionMapper.insert(version);
        return version;
    }

    /**
     * Computes the next version number of a document.
     *
     * <p>The minor part is bumped: a re-import replaces the content of the same logical document, which is
     * a revision of it and not a new lineage. The numbering itself is delegated so the upload path and the
     * chat path can never drift into two different version ladders for the same table.
     *
     * @param docId document business id
     * @return version number in {@code major.minor} form
     */
    private String nextVersionNumber(String docId) {
        return versionPlanner.nextMinor(documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().eq(DocumentVersion::getDocId, docId)));
    }

    private Chunk persistChunk(Document document, DocumentVersion version, ChatWindow window,
                               ParsedChatFile.ChatSession session, String text, boolean embeddingConfigured) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.SESSION_ID, session.getSessionId());
        metadata.put(ChunkMetadataKeys.SESSION_NAME, sessionMatcher.displayNameOf(session));
        metadata.put(ChunkMetadataKeys.SENDER, String.join(",", window.getSenders()));
        metadata.put(ChunkMetadataKeys.MSG_TIME, window.getStartTime());

        Chunk chunk = new Chunk();
        chunk.setChunkId(bizIdGenerator.chunkId());
        chunk.setKbId(document.getKbId());
        chunk.setDocId(document.getDocId());
        chunk.setDocumentVersionId(version.getVersionId());
        chunk.setContent(text);
        chunk.setChunkTextHash(chunkTextHasher.hash(text));
        chunk.setSeq(window.getSeq());
        chunk.setChunkType(ChunkType.CHAT_LOG);
        chunk.setEnabled(ENABLED);
        chunk.setMetadata(JsonUtil.toJson(metadata));
        chunk.setEmbeddingStatus(embeddingConfigured ? EmbeddingStatus.PENDING : EmbeddingStatus.SKIPPED);
        chunkMapper.insert(chunk);
        return chunk;
    }

    /**
     * Removes the chunks of every version of the document except the one just built.
     *
     * @param kbId         knowledge base business id
     * @param document     document record
     * @param keepVersionId version whose chunks must survive
     */
    private void removePreviousChunks(String kbId, Document document, String keepVersionId) {
        knowledgeBaseService.removeChunks(kbId, new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, document.getDocId())
                .ne(Chunk::getDocumentVersionId, keepVersionId));
    }

    /**
     * Cleaning rules of the chat path.
     *
     * @param config knowledge base index configuration
     * @return knowledge base rules with masking forced on when the deployment asks for it
     */
    private CleanRules chatCleanRules(KbIndexConfig config) {
        CleanRules rules = config.cleanRulesOrDefaults();
        return properties.getChatImport().isDesensitizeDefault() ? rules.withDesensitizeEnabled() : rules;
    }

    /**
     * Logical identities already present in a knowledge base.
     *
     * @param kbId knowledge base business id
     * @return document id per source key
     */
    private Map<String, String> existingSourceKeys(String kbId) {
        List<Document> documents = documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, kbId)
                .isNotNull(Document::getSourceKey));
        Map<String, String> byKey = new HashMap<>(documents.size());
        for (Document document : documents) {
            byKey.put(document.getSourceKey(), document.getDocId());
        }
        return byKey;
    }

    private ParsedChatFile readStaged(String objectKey) {
        ParsedChatFile parsed = JsonUtil.parse(
                new String(readAll(objectStorage.get(objectKey)), StandardCharsets.UTF_8),
                ParsedChatFile.class);
        if (parsed == null) {
            throw BizException.invalidParam("staged chat export is no longer available");
        }
        return parsed;
    }

    private String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw BizException.invalidParam("file name has no extension");
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw BizException.invalidParam("unsupported chat export extension: " + extension);
        }
        return extension;
    }

    private void writeObject(String objectKey, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        objectStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
    }

    private byte[] readAll(InputStream stream) {
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (Exception e) {
            log.error("read staged chat export failed, errorCode={}", ErrorCode.INTERNAL_ERROR, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "read staged chat export failed", e);
        }
    }
}
