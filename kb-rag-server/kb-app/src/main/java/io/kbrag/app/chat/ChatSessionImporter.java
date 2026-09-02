package io.kbrag.app.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.kbrag.app.annotation.AnnotationInheritanceService;
import io.kbrag.app.index.ChunkEmbedder;
import io.kbrag.app.index.ChunkIndexWriter;
import io.kbrag.app.index.DocumentVersionActivator;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.HashUtil;
import io.kbrag.common.util.JsonUtil;
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
import io.kbrag.domain.port.EmbeddingProvider;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one conversation into the knowledge base as a document version.
 *
 * <p>Split out of {@link ChatImportService} along the two halves of the chat import. The service owns the
 * conversation with the operator - parse the upload, match the sessions, stage them, hand back a preview,
 * then act on what was confirmed. This class owns what happens to a single confirmed conversation, which
 * is a full indexing build in miniature: aggregate the messages into windows, clean and render them,
 * persist the chunks, stamp the fingerprints, write the engines, retire the previous version's chunks and
 * activate the new one.
 *
 * <p>Nearly every collaborator here is read nowhere else in the import path - the window aggregator and
 * renderer, the cleaner, the embedder and index writer, the version activator, planner and fingerprint
 * factory, the annotation inheritance. That is the evidence the two halves are separate jobs rather than
 * one long one.
 *
 * <p><b>Ordering is a requirement, not an implementation detail.</b> The previous version's chunks are
 * removed only after the new ones are searchable, and the version is activated only after that removal;
 * a re-import must never leave the document with no findable content in between.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionImporter {

    /** File extension every chat document carries, whatever the export format was. */
    private static final String CHAT_FILE_EXT = "chat";

    /** Chunks are born enabled; an operator disables them afterwards. */
    private static final int ENABLED = 1;

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
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
    public String importSession(String kbId, ParsedChatFile.ChatSession session, KbIndexConfig config,
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
        // Written for every window, overlap configured or not: the retrieval side recognises an aggregation
        // window by the presence of the span, so writing it only when the overlap is on would make the
        // near duplicate merging silently inapplicable to everything imported before the switch was flipped.
        metadata.put(ChunkMetadataKeys.WINDOW_SEQ, window.getSeq());
        metadata.put(ChunkMetadataKeys.MSG_SPAN, List.of(window.getMsgSpanStart(), window.getMsgSpanEnd()));

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
     * @param kbId          knowledge base business id
     * @param document      document record
     * @param keepVersionId version whose chunks must survive
     */
    private void removePreviousChunks(String kbId, Document document, String keepVersionId) {
        knowledgeBaseService.removeChunks(kbId, new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getDocId, document.getDocId())
                .ne(Chunk::getDocumentVersionId, keepVersionId));
    }
}
